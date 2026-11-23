package gr.pnet.open5gs.swapevents;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for integration tests. The timer is off, so tests drive passes with {@code pollOnce()}.
 * Each test class gets its own context and random database, which is dropped before every test
 * and after the class.
 */
@SpringBootTest(properties = {
        "swapchecks.polling.enabled=false",
        "logging.structured.format.console=" })
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class MongoIntegrationTest {

    @Autowired
    protected MongoTemplate mongoTemplate;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        String database = TestMongo.randomDatabase();
        registry.add("spring.mongodb.uri", TestMongo::uri);
        registry.add("spring.mongodb.database", () -> database);
    }

    @BeforeEach
    void dropDatabaseBeforeEach() {
        mongoTemplate.getDb().drop();
    }

    @AfterAll
    void dropDatabaseAfterAll() {
        mongoTemplate.getDb().drop();
    }
}
