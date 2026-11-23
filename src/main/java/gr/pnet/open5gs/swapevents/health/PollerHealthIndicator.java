package gr.pnet.open5gs.swapevents.health;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import gr.pnet.open5gs.swapevents.services.PassResult;
import gr.pnet.open5gs.swapevents.services.SwapCheckPoller;

/**
 * Liveness: DOWN when no pass has finished, with any result, within the stall timeout. A failed
 * pass still counts as finished, so a Mongo outage keeps this UP; Mongo is checked by readiness.
 */
@Component
public class PollerHealthIndicator implements HealthIndicator {

    private final SwapCheckPoller poller;
    private final Duration stallTimeout;

    public PollerHealthIndicator(SwapCheckPoller poller,
            @Value("${swapchecks.stall-timeout}") Duration stallTimeout) {
        this.poller = poller;
        this.stallTimeout = stallTimeout;
    }

    @Override
    public Health health() {
        PassResult last = poller.lastResult();
        PassResult lastSuccess = poller.lastSuccess();
        Instant lastFinished = last != null ? last.finishedAt() : poller.startedAt();
        boolean stalled = Duration.between(lastFinished, Instant.now()).compareTo(stallTimeout) > 0;

        Health.Builder builder = stalled ? Health.down() : Health.up();
        builder.withDetail("lastResult", last != null ? last.status().tag() : "none");
        if (last != null) {
            builder.withDetail("lastFinishedTime", last.finishedAt().toString())
                    .withDetail("lastDuration", last.duration().toString());
        }
        if (lastSuccess != null) {
            builder.withDetail("lastSuccessTime", lastSuccess.finishedAt().toString())
                    .withDetail("subscribersSeen", lastSuccess.subscribersSeen());
        }
        if (stalled) {
            builder.withDetail("stallTimeout", stallTimeout.toString());
        }
        return builder.build();
    }
}
