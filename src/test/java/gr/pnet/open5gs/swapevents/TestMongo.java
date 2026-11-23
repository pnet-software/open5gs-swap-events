package gr.pnet.open5gs.swapevents;

import java.util.UUID;

import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The Mongo all integration tests share: {@code TEST_MONGODB_URI} when it is set (CI runs Mongo as
 * a service container), otherwise a Testcontainers {@code mongo:7} started on first use.
 */
public final class TestMongo {

    private static String uri;

    private TestMongo() {
    }

    @SuppressWarnings("resource") // shared by every test in the run
    public static synchronized String uri() {
        if (uri == null) {
            String fromEnv = System.getenv("TEST_MONGODB_URI");
            if (fromEnv != null && !fromEnv.isBlank()) {
                uri = fromEnv;
            } else {
                // Testcontainers removes the container when the JVM exits
                MongoDBContainer container = new MongoDBContainer("mongo:7");
                container.start();
                uri = container.getConnectionString();
            }
        }
        return uri;
    }

    /** A fresh database name, so test classes sharing one Mongo don't see each other's data. */
    public static String randomDatabase() {
        return "swapchecks_it_" + UUID.randomUUID().toString().replace("-", "");
    }
}
