package gr.pnet.open5gs.swapevents.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import gr.pnet.open5gs.swapevents.models.Open5gsSwapCheckDocument;
import gr.pnet.open5gs.swapevents.models.SubscriberIdentity;

/**
 * Decides how a subscriber's identity changes its swap_events row. Pure logic: no Mongo access,
 * and the existing row is never modified.
 */
@Component
public class SwapCheckUpdater {

    public enum Outcome {
        CREATED, UPDATED, UNCHANGED, SKIPPED
    }

    /** The outcome, and the row to save when it is {@code CREATED} or {@code UPDATED}. */
    public record Result(Outcome outcome, Optional<Open5gsSwapCheckDocument> toSave) {

        static Result of(Outcome outcome) {
            return new Result(outcome, Optional.empty());
        }

        static Result save(Outcome outcome, Open5gsSwapCheckDocument doc) {
            return new Result(outcome, Optional.of(doc));
        }
    }

    /**
     * @param existing      the current row for the subscriber's MSISDN, or null if there is none
     * @param timeIfNew     the timestamps of a new row
     * @param timeIfChanged the timestamp of a field that changed on an existing row
     */
    public Result apply(Open5gsSwapCheckDocument existing, SubscriberIdentity identity,
            Instant timeIfNew, Instant timeIfChanged) {

        if (!identity.hasMsisdnAndImsi()) {
            return Result.of(Outcome.SKIPPED);
        }
        String msisdn = identity.firstMsisdn();
        String imsi = identity.imsi();
        String imeisv = identity.firstImeisv();

        if (existing == null) {
            LocalDateTime time = toLocal(timeIfNew);
            return Result.save(Outcome.CREATED, new Open5gsSwapCheckDocument(msisdn, imsi, imeisv, time, time));
        }

        boolean imsiChanged = !Objects.equals(imsi, existing.getImsi());
        // An empty IMEISV never clears the stored one
        boolean imeisvChanged = !imeisv.isEmpty() && !Objects.equals(imeisv, existing.getImeisv());
        if (!imsiChanged && !imeisvChanged) {
            return Result.of(Outcome.UNCHANGED);
        }

        LocalDateTime time = toLocal(timeIfChanged);
        return Result.save(Outcome.UPDATED, new Open5gsSwapCheckDocument(
                msisdn,
                imsi,
                imeisvChanged ? imeisv : existing.getImeisv(),
                imsiChanged ? time : existing.getLastUpdatedImsi(),
                imeisvChanged ? time : existing.getLastUpdatedImeisv()));
    }

    private static LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
