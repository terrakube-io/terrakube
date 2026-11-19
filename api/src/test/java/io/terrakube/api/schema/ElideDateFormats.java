package io.terrakube.api.schema;

import com.yahoo.elide.Serdes;
import com.yahoo.elide.core.utils.coerce.converters.Serde;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Names the wire format of each date class by serializing a known instant through the serde Elide has registered for
 * it, so the model reflects what the API actually sends rather than what its configuration suggests.
 */
public class ElideDateFormats implements DateFormats {

    static final String SECONDS_UTC = "rfc3339-seconds-utc";
    static final String FRACTION_UTC = "rfc3339-fraction-utc";

    // Digits past the millisecond, so a serde that rounds or truncates them gets no format id.
    private static final String PROBE_FRACTION = "2026-11-19T15:28:21.123456Z";
    private static final Instant PROBE = Instant.parse(PROBE_FRACTION);
    private static final String PROBE_SECONDS = "2026-11-19T15:28:21Z";

    private static final SortedMap<String, DateFormat> DEFINITIONS = new TreeMap<>();

    static {
        DEFINITIONS.put(SECONDS_UTC, new DateFormat("rfc3339", "second", "utc-z", PROBE_SECONDS,
                "Whole seconds in UTC with a Z suffix."));
        DEFINITIONS.put(FRACTION_UTC, new DateFormat("rfc3339", "variable-fraction", "utc-z",
                PROBE_FRACTION,
                "UTC with a Z suffix and as many fractional-second digits as the value carries, possibly none."));
    }

    private final Serdes serdes;

    public ElideDateFormats(Serdes serdes) {
        this.serdes = serdes;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Optional<String> formatId(Class<?> javaType) {
        Serde serde = serdes.get(javaType);
        Optional<Object> probe = probe(javaType);
        if (serde == null || probe.isEmpty()) {
            return Optional.empty();
        }
        String wire = String.valueOf(serde.serialize(probe.get()));
        if (wire.equals(PROBE_SECONDS)) {
            return Optional.of(SECONDS_UTC);
        }
        if (wire.equals(PROBE_FRACTION)) {
            return Optional.of(FRACTION_UTC);
        }
        return Optional.empty();
    }

    @Override
    public SortedMap<String, DateFormat> definitions() {
        return new TreeMap<>(DEFINITIONS);
    }

    private static Optional<Object> probe(Class<?> javaType) {
        if (javaType == Instant.class) {
            return Optional.of(PROBE);
        }
        if (javaType == OffsetDateTime.class) {
            return Optional.of(PROBE.atOffset(ZoneOffset.UTC));
        }
        if (javaType == ZonedDateTime.class) {
            return Optional.of(PROBE.atZone(ZoneOffset.UTC));
        }
        if (javaType == java.sql.Timestamp.class) {
            return Optional.of(java.sql.Timestamp.from(PROBE));
        }
        if (Date.class.isAssignableFrom(javaType)) {
            // java.util.Date and its java.sql subclasses all take epoch milliseconds
            try {
                return Optional.of(javaType.getConstructor(long.class).newInstance(PROBE.toEpochMilli()));
            } catch (ReflectiveOperationException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
