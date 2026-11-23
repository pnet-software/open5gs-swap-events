package gr.pnet.open5gs.swapevents.services;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;

import gr.pnet.open5gs.swapevents.models.Open5gsSwapCheckDocument;
import gr.pnet.open5gs.swapevents.models.SubscriberIdentity;
import gr.pnet.open5gs.swapevents.services.PassResult.Status;
import gr.pnet.open5gs.swapevents.services.SwapCheckUpdater.Result;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Compares every Open5GS subscriber with its swap_events row and saves the rows that changed.
 * {@link PollingSchedule} runs a pass every poll interval.
 */
@Service
public class SwapCheckPoller {

    private static final Logger log = LoggerFactory.getLogger(SwapCheckPoller.class);

    static final String SUBSCRIBERS = "subscribers";
    // K, OPc and the rest of the subscriber never leave the server
    private static final Bson PROJECTION = Projections.include("imsi", "msisdn", "imeisv");
    // Newest first, so the newest of several subscribers sharing an MSISDN owns its row
    private static final Bson NEWEST_FIRST = Sorts.descending("_id");
    private static final int BATCH_SIZE = 500;

    private final MongoTemplate mongoTemplate;
    private final SwapCheckUpdater updater;

    private final Counter createdCounter;
    private final Counter updatedCounter;
    private final Counter missingFieldCounter;
    private final Counter duplicateMsisdnCounter;
    private final Map<Status, Timer> passTimers = new EnumMap<>(Status.class);

    private final Instant startedAt = Instant.now();
    private volatile boolean stopping;
    private volatile Instant lastPassStartedAt;
    private volatile PassResult lastResult;
    private volatile PassResult lastSuccess;

    public SwapCheckPoller(MongoTemplate mongoTemplate, SwapCheckUpdater updater, MeterRegistry meterRegistry) {
        this.mongoTemplate = mongoTemplate;
        this.updater = updater;

        createdCounter = changesCounter("created", meterRegistry);
        updatedCounter = changesCounter("updated", meterRegistry);
        missingFieldCounter = skippedCounter("missing_field", meterRegistry);
        duplicateMsisdnCounter = skippedCounter("duplicate_msisdn", meterRegistry);
        for (Status status : Status.values()) {
            passTimers.put(status, Timer.builder("swapchecks.poll")
                    .description("Passes over the subscribers")
                    .tag("result", status.tag())
                    .register(meterRegistry));
        }
        Gauge.builder("swapchecks.last.success.time", this,
                p -> p.lastSuccess == null ? 0 : p.lastSuccess.finishedAt().toEpochMilli() / 1000.0)
                .description("Epoch seconds when the last successful pass finished, 0 before the first one")
                .register(meterRegistry);
        Gauge.builder("swapchecks.subscribers", this,
                p -> p.lastSuccess == null ? 0 : p.lastSuccess.subscribersSeen())
                .description("Subscribers seen in the last successful pass")
                .register(meterRegistry);
    }

    /**
     * Runs one pass. A Mongo error ends the pass as {@code FAILED} instead of throwing, and
     * shutdown ends it as {@code ABORTED}. Rows saved before that stay saved.
     */
    public PassResult pollOnce() {
        Instant start = Instant.now();
        lastPassStartedAt = start;
        Counts counts = new Counts();
        Status status;
        try {
            status = runPass(counts);
        } catch (RuntimeException e) {
            log.error("Pass failed after {} subscribers", counts.seen, e);
            status = Status.FAILED;
        }
        PassResult result = counts.toResult(status, start, Instant.now());
        record(result);
        return result;
    }

    private Status runPass(Counts counts) {
        // Only this service writes swap_events, so the map can't go stale during the pass
        Map<String, Open5gsSwapCheckDocument> existing = new HashMap<>();
        for (Open5gsSwapCheckDocument doc : mongoTemplate.findAll(Open5gsSwapCheckDocument.class)) {
            existing.put(doc.getMsisdn(), doc);
        }
        Set<String> seenMsisdns = new HashSet<>();

        try (MongoCursor<Document> cursor = mongoTemplate.getCollection(SUBSCRIBERS).find()
                .projection(PROJECTION)
                .sort(NEWEST_FIRST)
                .batchSize(BATCH_SIZE)
                .cursor()) {
            while (cursor.hasNext()) {
                if (stopping) {
                    return Status.ABORTED;
                }
                process(SubscriberIdentity.from(cursor.next()), existing, seenMsisdns, counts);
            }
        }
        return Status.SUCCESS;
    }

    private void process(SubscriberIdentity identity, Map<String, Open5gsSwapCheckDocument> existing,
            Set<String> seenMsisdns, Counts counts) {
        counts.seen++;
        if (!identity.hasMsisdnAndImsi()) {
            log.debug("Skipping subscriber {} with no MSISDN or IMSI", identity.id());
            counts.missingField++;
            missingFieldCounter.increment();
            return;
        }
        String msisdn = identity.firstMsisdn();
        if (!seenMsisdns.add(msisdn)) {
            log.debug("Skipping subscriber {}: MSISDN {} belongs to a newer subscriber", identity.id(), msisdn);
            counts.duplicateMsisdn++;
            duplicateMsisdnCounter.increment();
            return;
        }

        // Read after the change happened, so never earlier than the real change time
        Instant now = Instant.now();
        Instant createdAt = identity.createdAt();
        Result result = updater.apply(existing.get(msisdn), identity, createdAt != null ? createdAt : now, now);
        result.toSave().ifPresent(mongoTemplate::save);

        switch (result.outcome()) {
            case CREATED -> {
                counts.created++;
                createdCounter.increment();
                log.info("Created swap_events row for MSISDN {}", msisdn);
            }
            case UPDATED -> {
                counts.updated++;
                updatedCounter.increment();
                log.info("Updated swap_events row for MSISDN {}", msisdn);
            }
            case UNCHANGED -> counts.unchanged++;
            // SKIPPED can't happen: the MSISDN and IMSI were checked above
            case SKIPPED -> counts.missingField++;
        }
    }

    private void record(PassResult result) {
        passTimers.get(result.status()).record(result.duration());
        boolean first = lastResult == null;
        lastResult = result;
        if (result.status() == Status.SUCCESS) {
            lastSuccess = result;
        }

        if (result.duplicateMsisdn() > 0) {
            log.warn("{} subscribers share an MSISDN with a newer subscriber; the newest one owns the swap_events row",
                    result.duplicateMsisdn());
        }
        boolean quiet = result.status() == Status.SUCCESS && result.created() == 0 && result.updated() == 0;
        log.atLevel(quiet && !first ? Level.DEBUG : Level.INFO).log(
                "Pass {} in {} ms: {} subscribers, {} created, {} updated, {} unchanged, {} missing MSISDN or IMSI",
                result.status().tag(), result.duration().toMillis(), result.subscribersSeen(),
                result.created(), result.updated(), result.unchanged(), result.missingField());
    }

    /** Makes a running pass stop at the next subscriber. */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        setStopping(true);
    }

    // Package-private so tests can stop a pass and clear the flag again
    void setStopping(boolean stopping) {
        this.stopping = stopping;
    }

    /** When this bean was created, which is the health baseline before the first pass. */
    public Instant startedAt() {
        return startedAt;
    }

    /** When the latest pass started, or null before the first one. */
    public Instant lastPassStartedAt() {
        return lastPassStartedAt;
    }

    /** The latest finished pass, or null before the first one. */
    public PassResult lastResult() {
        return lastResult;
    }

    /** The latest successful pass, or null before the first one. */
    public PassResult lastSuccess() {
        return lastSuccess;
    }

    private static Counter changesCounter(String outcome, MeterRegistry meterRegistry) {
        return Counter.builder("swapchecks.changes")
                .description("swap_events rows created or updated")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static Counter skippedCounter(String reason, MeterRegistry meterRegistry) {
        return Counter.builder("swapchecks.skipped")
                .description("Subscribers skipped by a pass")
                .tag("reason", reason)
                .register(meterRegistry);
    }

    private static final class Counts {
        int seen;
        int created;
        int updated;
        int unchanged;
        int missingField;
        int duplicateMsisdn;

        PassResult toResult(Status status, Instant start, Instant end) {
            return new PassResult(status, start, end, seen, created, updated, unchanged, missingField, duplicateMsisdn);
        }
    }
}
