package gr.pnet.open5gs.swapevents.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import gr.pnet.open5gs.swapevents.models.Open5gsSwapCheckDocument;
import gr.pnet.open5gs.swapevents.services.SwapCheckPoller;
import gr.pnet.open5gs.swapevents.services.SwapCheckUpdater;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PollerHealthIndicatorTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final SwapCheckPoller poller = new SwapCheckPoller(mongoTemplate, new SwapCheckUpdater(), new SimpleMeterRegistry());

    @Test
    void upBeforeTheFirstPassWithinTheStallTimeout() {
        Health health = new PollerHealthIndicator(poller, Duration.ofMinutes(10)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("lastResult", "none");
    }

    @Test
    void downWhenNoPassFinishedWithinTheStallTimeout() throws InterruptedException {
        PollerHealthIndicator indicator = new PollerHealthIndicator(poller, Duration.ofMillis(1));
        Thread.sleep(20);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void failedPassCountsAsFinished() {
        when(mongoTemplate.findAll(Open5gsSwapCheckDocument.class))
                .thenThrow(new DataAccessResourceFailureException("Mongo is down"));
        poller.pollOnce();

        Health health = new PollerHealthIndicator(poller, Duration.ofMinutes(10)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("lastResult", "failed")
                .containsKeys("lastFinishedTime", "lastDuration")
                .doesNotContainKey("lastSuccessTime");
    }
}
