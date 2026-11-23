package gr.pnet.open5gs.swapevents.models;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * The identity fields of an Open5GS subscriber. Built from the raw document and ignoring every
 * other field, so changes to the rest of the subscriber schema can't break this service.
 */
public record SubscriberIdentity(ObjectId id, String imsi, List<String> msisdn, List<String> imeisv) {

    public SubscriberIdentity {
        msisdn = msisdn == null ? List.of() : msisdn;
        imeisv = imeisv == null ? List.of() : imeisv;
    }

    public static SubscriberIdentity from(Document doc) {
        return new SubscriberIdentity(
                doc.get("_id") instanceof ObjectId oid ? oid : null,
                doc.get("imsi") instanceof String s ? s : null,
                strings(doc.get("msisdn")),
                strings(doc.get("imeisv")));
    }

    /** The first MSISDN, or "" if there is none. */
    public String firstMsisdn() {
        return first(msisdn);
    }

    /** The first IMEISV, or "" if there is none. */
    public String firstImeisv() {
        return first(imeisv);
    }

    public boolean hasMsisdnAndImsi() {
        return !firstMsisdn().isBlank() && imsi != null && !imsi.isBlank();
    }

    /** The creation time encoded in the ObjectId, or null if {@code _id} isn't an ObjectId. */
    public Instant createdAt() {
        return id == null ? null : Instant.ofEpochSecond(id.getTimestamp());
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        if (value instanceof String s) {
            return List.of(s);
        }
        return List.of();
    }

    private static String first(List<String> values) {
        return values.isEmpty() ? "" : values.get(0);
    }
}
