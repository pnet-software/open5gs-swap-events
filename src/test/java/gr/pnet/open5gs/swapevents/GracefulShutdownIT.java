package gr.pnet.open5gs.swapevents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import gr.pnet.open5gs.swapevents.services.PassResult.Status;
import gr.pnet.open5gs.swapevents.services.SwapCheckPoller;

/** Runs the app with the timer on, as in production, and closes it in the middle of a pass. */
class GracefulShutdownIT {

    // Enough that the first pass is still saving rows when the context closes
    private static final int SUBSCRIBERS = 20_000;

    @Test
    void closingTheContextMidPassFinishesWithinSeconds() throws InterruptedException {
        String database = TestMongo.randomDatabase();
        try (MongoClient client = MongoClients.create(TestMongo.uri())) {
            MongoDatabase db = client.getDatabase(database);
            try {
                db.getCollection("subscribers").insertMany(IntStream.range(0, SUBSCRIBERS)
                        .mapToObj(i -> new Document("imsi", "00101%010d".formatted(i))
                                .append("msisdn", List.of("30690%07d".formatted(i))))
                        .toList());

                ConfigurableApplicationContext context = new SpringApplicationBuilder(Open5gsSwapEventsApplication.class)
                        .run("--spring.mongodb.uri=" + TestMongo.uri(),
                                "--spring.mongodb.database=" + database,
                                "--server.port=0",
                                "--logging.structured.format.console=",
                                "--logging.level.gr.pnet=WARN");
                try {
                    SwapCheckPoller poller = context.getBean(SwapCheckPoller.class);
                    awaitTrue(() -> db.getCollection("swap_events").countDocuments() > 0, Duration.ofSeconds(30));

                    long start = System.nanoTime();
                    context.close();
                    Duration closing = Duration.ofNanos(System.nanoTime() - start);

                    assertThat(closing).isLessThan(Duration.ofSeconds(5));
                    assertThat(poller.lastResult().status()).isEqualTo(Status.ABORTED);
                } finally {
                    context.close();
                }
            } finally {
                db.drop();
            }
        }
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            Thread.sleep(20);
        }
    }
}
