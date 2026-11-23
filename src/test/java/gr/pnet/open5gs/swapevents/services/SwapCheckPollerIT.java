package gr.pnet.open5gs.swapevents.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import gr.pnet.open5gs.swapevents.MongoIntegrationTest;
import gr.pnet.open5gs.swapevents.models.Open5gsSwapCheckDocument;
import gr.pnet.open5gs.swapevents.services.PassResult.Status;
import io.micrometer.core.instrument.MeterRegistry;

@MockitoSpyBean(types = MongoTemplate.class)
class SwapCheckPollerIT extends MongoIntegrationTest {

    private static final Instant JAN = Instant.parse("2024-01-01T10:00:00Z");
    private static final Instant FEB = Instant.parse("2024-02-01T10:00:00Z");
    private static final Instant MAR = Instant.parse("2024-03-01T10:00:00Z");

    @Autowired
    private SwapCheckPoller poller;

    @Autowired
    private MeterRegistry meterRegistry;

    @AfterEach
    void clearStopping() {
        poller.setStopping(false);
    }

    @Test
    void firstPassCreatesOneRowPerSubscriberStampedWithTheObjectIdTime() {
        insertSubscriber(JAN, "001010000000001", "306900000001", "3534900698733190");
        insertSubscriber(FEB, "001010000000002", "306900000002", null);

        PassResult result = poller.pollOnce();

        assertThat(result.status()).isEqualTo(Status.SUCCESS);
        assertThat(result.subscribersSeen()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
        assertThat(row("306900000001")).isEqualTo(new Open5gsSwapCheckDocument(
                "306900000001", "001010000000001", "3534900698733190", local(JAN), local(JAN)));
        assertThat(row("306900000002")).isEqualTo(new Open5gsSwapCheckDocument(
                "306900000002", "001010000000002", "", local(FEB), local(FEB)));
    }

    @Test
    void imsiChangeMovesOnlyLastUpdatedImsi() {
        ObjectId id = insertSubscriber(JAN, "001010000000001", "306900000001", "3534900698733190");
        poller.pollOnce();

        subscribers().updateOne(Filters.eq("_id", id), Updates.set("imsi", "001010000000009"));
        PassResult result = poller.pollOnce();

        assertThat(result.updated()).isEqualTo(1);
        Open5gsSwapCheckDocument row = row("306900000001");
        assertThat(row.getImsi()).isEqualTo("001010000000009");
        assertThat(row.getLastUpdatedImsi()).isBetween(passStart(result), local(result.finishedAt()));
        assertThat(row.getImeisv()).isEqualTo("3534900698733190");
        assertThat(row.getLastUpdatedImeisv()).isEqualTo(local(JAN));
    }

    @Test
    void imeisvChangeMovesOnlyLastUpdatedImeisv() {
        ObjectId id = insertSubscriber(JAN, "001010000000001", "306900000001", "3534900698733190");
        poller.pollOnce();

        subscribers().updateOne(Filters.eq("_id", id), Updates.set("imeisv", List.of("3534900698733191")));
        PassResult result = poller.pollOnce();

        assertThat(result.updated()).isEqualTo(1);
        Open5gsSwapCheckDocument row = row("306900000001");
        assertThat(row.getImeisv()).isEqualTo("3534900698733191");
        assertThat(row.getLastUpdatedImeisv()).isBetween(passStart(result), local(result.finishedAt()));
        assertThat(row.getImsi()).isEqualTo("001010000000001");
        assertThat(row.getLastUpdatedImsi()).isEqualTo(local(JAN));
    }

    @Test
    void emptyImeisvDoesNotClearTheStoredOne() {
        ObjectId id = insertSubscriber(JAN, "001010000000001", "306900000001", "3534900698733190");
        poller.pollOnce();
        Open5gsSwapCheckDocument before = row("306900000001");

        subscribers().updateOne(Filters.eq("_id", id), Updates.set("imeisv", List.of()));
        PassResult result = poller.pollOnce();

        assertThat(result.updated()).isZero();
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(row("306900000001")).isEqualTo(before);
    }

    @Test
    void secondPassWithNoChangesWritesNothing() {
        insertSubscriber(JAN, "001010000000001", "306900000001", "3534900698733190");
        insertSubscriber(FEB, "001010000000002", "306900000002", "3534900698733191");
        poller.pollOnce();
        List<Open5gsSwapCheckDocument> before = mongoTemplate.findAll(Open5gsSwapCheckDocument.class);
        clearInvocations(mongoTemplate);

        PassResult result = poller.pollOnce();

        assertThat(result.status()).isEqualTo(Status.SUCCESS);
        assertThat(result.created()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.unchanged()).isEqualTo(2);
        verify(mongoTemplate, never()).save(any(Open5gsSwapCheckDocument.class));
        assertThat(mongoTemplate.findAll(Open5gsSwapCheckDocument.class)).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void newestSubscriberOwnsASharedMsisdnAcrossPasses() {
        insertSubscriber(JAN, "001010000000001", "306900000001", "3534900698733190");
        insertSubscriber(FEB, "001010000000002", "306900000001", "3534900698733191");
        Open5gsSwapCheckDocument expected = new Open5gsSwapCheckDocument(
                "306900000001", "001010000000002", "3534900698733191", local(FEB), local(FEB));

        for (int pass = 1; pass <= 3; pass++) {
            PassResult result = poller.pollOnce();

            assertThat(result.duplicateMsisdn()).isEqualTo(1);
            assertThat(result.created() + result.updated()).isEqualTo(pass == 1 ? 1 : 0);
            assertThat(row("306900000001")).isEqualTo(expected);
        }
    }

    @Test
    void subscribersWithoutMsisdnOrImsiAreSkipped() {
        double skippedBefore = meterRegistry.counter("swapchecks.skipped", "reason", "missing_field").count();
        subscribers().insertOne(new Document("imsi", "001010000000001").append("msisdn", List.of()));
        subscribers().insertOne(new Document("msisdn", List.of("306900000002")));

        PassResult result = poller.pollOnce();

        assertThat(result.missingField()).isEqualTo(2);
        assertThat(result.created()).isZero();
        assertThat(mongoTemplate.findAll(Open5gsSwapCheckDocument.class)).isEmpty();
        assertThat(meterRegistry.counter("swapchecks.skipped", "reason", "missing_field").count())
                .isEqualTo(skippedBefore + 2);
    }

    @Test
    void stoppingMidPassAbortsItAndTheNextPassCompletes() {
        insertSubscriber(JAN, "001010000000001", "306900000001", null);
        insertSubscriber(FEB, "001010000000002", "306900000002", null);
        insertSubscriber(MAR, "001010000000003", "306900000003", null);
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            poller.setStopping(true);
            return saved;
        }).when(mongoTemplate).save(any(Open5gsSwapCheckDocument.class));

        PassResult aborted = poller.pollOnce();

        assertThat(aborted.status()).isEqualTo(Status.ABORTED);
        assertThat(aborted.created()).isEqualTo(1);
        assertThat(mongoTemplate.findAll(Open5gsSwapCheckDocument.class)).hasSize(1);

        doCallRealMethod().when(mongoTemplate).save(any(Open5gsSwapCheckDocument.class));
        poller.setStopping(false);
        PassResult next = poller.pollOnce();

        assertThat(next.status()).isEqualTo(Status.SUCCESS);
        assertThat(next.created()).isEqualTo(2);
        assertThat(next.unchanged()).isEqualTo(1);
        assertThat(mongoTemplate.findAll(Open5gsSwapCheckDocument.class)).hasSize(3);
    }

    @Test
    void mongoErrorFailsThePassWithoutThrowing() {
        long failedBefore = meterRegistry.timer("swapchecks.poll", "result", "failed").count();
        insertSubscriber(JAN, "001010000000001", "306900000001", null);
        doThrow(new DataAccessResourceFailureException("Mongo is down"))
                .when(mongoTemplate).save(any(Open5gsSwapCheckDocument.class));

        PassResult result = poller.pollOnce();

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(poller.lastResult()).isEqualTo(result);
        assertThat(poller.lastSuccess()).isNotEqualTo(result);
        assertThat(meterRegistry.timer("swapchecks.poll", "result", "failed").count()).isEqualTo(failedBefore + 1);
    }

    private ObjectId insertSubscriber(Instant createdAt, String imsi, String msisdn, String imeisv) {
        ObjectId id = new ObjectId(Date.from(createdAt));
        subscribers().insertOne(new Document("_id", id)
                .append("imsi", imsi)
                .append("msisdn", List.of(msisdn))
                .append("imeisv", imeisv == null ? List.of() : List.of(imeisv))
                .append("security", new Document("k", "465B5CE8B199B49FAA5F0A2EE238A6BC")
                        .append("opc", "E8ED289DEBA952E4283B54E88E6183CA")));
        return id;
    }

    private MongoCollection<Document> subscribers() {
        return mongoTemplate.getCollection(SwapCheckPoller.SUBSCRIBERS);
    }

    private Open5gsSwapCheckDocument row(String msisdn) {
        return mongoTemplate.findById(msisdn, Open5gsSwapCheckDocument.class);
    }

    // Mongo stores milliseconds, so a stamp taken right at the start can round below it
    private static LocalDateTime passStart(PassResult result) {
        return local(result.startedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
