package gr.pnet.open5gs.swapevents.services;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * What one pass over the subscribers did.
 *
 * @param subscribersSeen subscriber docs read, including skipped ones
 */
public record PassResult(
        Status status,
        Instant startedAt,
        Instant finishedAt,
        int subscribersSeen,
        int created,
        int updated,
        int unchanged,
        int missingField,
        int duplicateMsisdn) {

    public enum Status {
        SUCCESS, FAILED, ABORTED;

        /** The lower-case name used in metric tags and health details. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }
}
