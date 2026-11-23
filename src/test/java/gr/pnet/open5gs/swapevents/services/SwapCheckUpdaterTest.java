package gr.pnet.open5gs.swapevents.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import gr.pnet.open5gs.swapevents.models.Open5gsSwapCheckDocument;
import gr.pnet.open5gs.swapevents.models.SubscriberIdentity;
import gr.pnet.open5gs.swapevents.services.SwapCheckUpdater.Outcome;
import gr.pnet.open5gs.swapevents.services.SwapCheckUpdater.Result;

class SwapCheckUpdaterTest {

    private static final String MSISDN = "306900000001";
    private static final String IMSI = "001010000000001";
    private static final String IMEISV = "3534900698733190";

    private static final Instant CREATED_AT = Instant.parse("2024-01-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2025-06-01T12:00:00Z");
    private static final LocalDateTime EARLIER = LocalDateTime.of(2024, 3, 1, 8, 0);

    private final SwapCheckUpdater updater = new SwapCheckUpdater();

    @Test
    void newSubscriberCreatesARowStampedWithTheCreationTime() {
        Result result = updater.apply(null, identity(IMSI, MSISDN, IMEISV), CREATED_AT, NOW);

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(result.toSave()).contains(
                new Open5gsSwapCheckDocument(MSISDN, IMSI, IMEISV, local(CREATED_AT), local(CREATED_AT)));
    }

    @Test
    void newSubscriberWithoutImeisvStoresAnEmptyOne() {
        Result result = updater.apply(null, identity(IMSI, MSISDN, null), CREATED_AT, NOW);

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(result.toSave()).get().extracting(Open5gsSwapCheckDocument::getImeisv).isEqualTo("");
    }

    @Test
    void imsiChangeMovesOnlyLastUpdatedImsi() {
        Result result = updater.apply(row(IMSI, IMEISV), identity("001010000000002", MSISDN, IMEISV), CREATED_AT, NOW);

        assertThat(result.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(result.toSave()).contains(
                new Open5gsSwapCheckDocument(MSISDN, "001010000000002", IMEISV, local(NOW), EARLIER));
    }

    @Test
    void imeisvChangeMovesOnlyLastUpdatedImeisv() {
        Result result = updater.apply(row(IMSI, IMEISV), identity(IMSI, MSISDN, "3534900698733191"), CREATED_AT, NOW);

        assertThat(result.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(result.toSave()).contains(
                new Open5gsSwapCheckDocument(MSISDN, IMSI, "3534900698733191", EARLIER, local(NOW)));
    }

    @Test
    void imsiAndImeisvChangeMoveBothTimestamps() {
        Result result = updater.apply(row(IMSI, IMEISV),
                identity("001010000000002", MSISDN, "3534900698733191"), CREATED_AT, NOW);

        assertThat(result.toSave()).contains(
                new Open5gsSwapCheckDocument(MSISDN, "001010000000002", "3534900698733191", local(NOW), local(NOW)));
    }

    @Test
    void emptyImeisvDoesNotClearTheStoredOne() {
        Result result = updater.apply(row(IMSI, IMEISV), identity(IMSI, MSISDN, null), CREATED_AT, NOW);

        assertThat(result.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(result.toSave()).isEmpty();
    }

    @Test
    void emptyImeisvKeepsTheStoredOneWhenTheImsiChanges() {
        Result result = updater.apply(row(IMSI, IMEISV), identity("001010000000002", MSISDN, null), CREATED_AT, NOW);

        assertThat(result.toSave()).contains(
                new Open5gsSwapCheckDocument(MSISDN, "001010000000002", IMEISV, local(NOW), EARLIER));
    }

    @Test
    void sameIdentityIsUnchanged() {
        Result result = updater.apply(row(IMSI, IMEISV), identity(IMSI, MSISDN, IMEISV), CREATED_AT, NOW);

        assertThat(result.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(result.toSave()).isEmpty();
    }

    @Test
    void storedRowWithNullFieldsIsComparedSafely() {
        Open5gsSwapCheckDocument existing = new Open5gsSwapCheckDocument(MSISDN, null, null, null, null);

        Result result = updater.apply(existing, identity(IMSI, MSISDN, IMEISV), CREATED_AT, NOW);

        assertThat(result.toSave()).contains(
                new Open5gsSwapCheckDocument(MSISDN, IMSI, IMEISV, local(NOW), local(NOW)));
    }

    @Test
    void missingMsisdnIsSkipped() {
        Result result = updater.apply(null, identity(IMSI, null, IMEISV), CREATED_AT, NOW);

        assertThat(result.outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(result.toSave()).isEmpty();
    }

    @Test
    void missingImsiIsSkipped() {
        Result result = updater.apply(row(IMSI, IMEISV), identity(null, MSISDN, IMEISV), CREATED_AT, NOW);

        assertThat(result.outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(result.toSave()).isEmpty();
    }

    @Test
    void existingRowIsNotModified() {
        Open5gsSwapCheckDocument existing = row(IMSI, IMEISV);

        updater.apply(existing, identity("001010000000002", MSISDN, "3534900698733191"), CREATED_AT, NOW);

        assertThat(existing).isEqualTo(row(IMSI, IMEISV));
    }

    private static SubscriberIdentity identity(String imsi, String msisdn, String imeisv) {
        return new SubscriberIdentity(new ObjectId(), imsi,
                msisdn == null ? List.of() : List.of(msisdn),
                imeisv == null ? List.of() : List.of(imeisv));
    }

    private static Open5gsSwapCheckDocument row(String imsi, String imeisv) {
        return new Open5gsSwapCheckDocument(MSISDN, imsi, imeisv, EARLIER, EARLIER);
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
