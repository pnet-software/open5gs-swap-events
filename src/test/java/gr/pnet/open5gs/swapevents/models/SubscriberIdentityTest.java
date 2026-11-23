package gr.pnet.open5gs.swapevents.models;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class SubscriberIdentityTest {

    @Test
    void readsTheIdentityFieldsAndIgnoresTheRest() {
        ObjectId id = new ObjectId(Date.from(Instant.parse("2024-01-01T10:00:00Z")));
        Document doc = new Document("_id", id)
                .append("imsi", "001010000000001")
                .append("msisdn", List.of("306900000001", "306900000002"))
                .append("imeisv", List.of("3534900698733190"))
                .append("security", new Document("k", "465B5CE8B199B49FAA5F0A2EE238A6BC"))
                .append("slice", List.of(new Document("sst", 1)));

        SubscriberIdentity identity = SubscriberIdentity.from(doc);

        assertThat(identity.id()).isEqualTo(id);
        assertThat(identity.imsi()).isEqualTo("001010000000001");
        assertThat(identity.firstMsisdn()).isEqualTo("306900000001");
        assertThat(identity.firstImeisv()).isEqualTo("3534900698733190");
        assertThat(identity.hasMsisdnAndImsi()).isTrue();
        assertThat(identity.createdAt()).isEqualTo(Instant.parse("2024-01-01T10:00:00Z"));
    }

    @Test
    void toleratesFieldsOfAnUnexpectedType() {
        Document doc = new Document("_id", "not-an-object-id")
                .append("imsi", 1010000000001L)
                .append("msisdn", "306900000001")
                .append("imeisv", Arrays.asList(null, 3534900698733190L));

        SubscriberIdentity identity = SubscriberIdentity.from(doc);

        assertThat(identity.id()).isNull();
        assertThat(identity.createdAt()).isNull();
        assertThat(identity.imsi()).isNull();
        assertThat(identity.firstMsisdn()).isEqualTo("306900000001");
        assertThat(identity.firstImeisv()).isEqualTo("3534900698733190");
        assertThat(identity.hasMsisdnAndImsi()).isFalse();
    }

    @Test
    void missingFieldsAreEmpty() {
        SubscriberIdentity identity = SubscriberIdentity.from(new Document("imsi", "001010000000001"));

        assertThat(identity.firstMsisdn()).isEmpty();
        assertThat(identity.firstImeisv()).isEmpty();
        assertThat(identity.hasMsisdnAndImsi()).isFalse();
    }

    @Test
    void blankMsisdnOrImsiCountsAsMissing() {
        assertThat(new SubscriberIdentity(null, "001010000000001", List.of(" "), null).hasMsisdnAndImsi()).isFalse();
        assertThat(new SubscriberIdentity(null, "", List.of("306900000001"), null).hasMsisdnAndImsi()).isFalse();
    }
}
