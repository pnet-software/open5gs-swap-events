package gr.pnet.open5gs.swapevents.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs a pass every poll interval. A fixed delay on the single scheduler thread means passes
 * never overlap, and after a failed pass the interval acts as the backoff.
 * Tests switch it off with {@code swapchecks.polling.enabled=false} and call the poller directly.
 */
@Component
@ConditionalOnProperty(name = "swapchecks.polling.enabled", matchIfMissing = true)
public class PollingSchedule {

    private static final Logger log = LoggerFactory.getLogger(PollingSchedule.class);

    private final SwapCheckPoller poller;

    public PollingSchedule(SwapCheckPoller poller) {
        this.poller = poller;
    }

    @Scheduled(fixedDelayString = "${swapchecks.poll-interval}", initialDelay = 0)
    public void poll() {
        try {
            poller.pollOnce();
        } catch (RuntimeException e) {
            log.error("Unexpected error in pass", e);
        }
    }
}
