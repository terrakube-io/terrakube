package io.terrakube.api.plugin.elide;

import com.yahoo.elide.Serdes;
import com.yahoo.elide.core.utils.coerce.converters.Serde;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class ElideSerdeConfigurationTest {

    private ElideSerdeConfiguration configuration;
    private Serdes.SerdesBuilder serdesBuilder;

    @BeforeEach
    void setUp() {
        configuration = new ElideSerdeConfiguration();
        serdesBuilder = Serdes.builder().withDefaults().withISO8601Dates("yyyy-MM-dd'T'HH:mm'Z'", java.util.TimeZone.getTimeZone("UTC"));
        configuration.secondPrecisionDateSerdes().customize(serdesBuilder);
    }

    @Test
    void testSerializationProducesUtcSecondPrecision() {
        @SuppressWarnings("unchecked")
        Serde<Object, Date> dateSerde = (Serde<Object, Date>) serdesBuilder.build().get(Date.class);
        assertNotNull(dateSerde);

        Date testDate = Date.from(Instant.parse("2026-01-15T10:20:42.987Z"));
        Object serialized = dateSerde.serialize(testDate);

        assertEquals("2026-01-15T10:20:42Z", serialized);
    }

    @Test
    void testDeserializeExactUtc() {
        @SuppressWarnings("unchecked")
        Serde<Object, Date> dateSerde = (Serde<Object, Date>) serdesBuilder.build().get(Date.class);

        Date date = dateSerde.deserialize("2026-01-15T10:20:42Z");
        assertEquals(Instant.parse("2026-01-15T10:20:42Z"), date.toInstant());
    }

    @Test
    void testDeserializeWithMilliseconds() {
        @SuppressWarnings("unchecked")
        Serde<Object, Date> dateSerde = (Serde<Object, Date>) serdesBuilder.build().get(Date.class);

        Date date = dateSerde.deserialize("2026-01-15T10:20:42.123Z");
        assertEquals(Instant.parse("2026-01-15T10:20:42.123Z"), date.toInstant());

        Date dateZeroMillis = dateSerde.deserialize("2026-01-15T10:20:42.000Z");
        assertEquals(Instant.parse("2026-01-15T10:20:42Z"), dateZeroMillis.toInstant());
    }

    @Test
    void testDeserializeWithTimezoneOffsets() {
        @SuppressWarnings("unchecked")
        Serde<Object, Date> dateSerde = (Serde<Object, Date>) serdesBuilder.build().get(Date.class);

        // 12:20:42 UTC+2 is 10:20:42 UTC
        Date datePlusTwo = dateSerde.deserialize("2026-01-15T12:20:42+02:00");
        assertEquals(Instant.parse("2026-01-15T10:20:42Z"), datePlusTwo.toInstant());

        // 05:20:42 UTC-5 is 10:20:42 UTC
        Date dateMinusFive = dateSerde.deserialize("2026-01-15T05:20:42-05:00");
        assertEquals(Instant.parse("2026-01-15T10:20:42Z"), dateMinusFive.toInstant());
    }

    @Test
    void testDeserializeMinuteOnlyFallback() {
        @SuppressWarnings("unchecked")
        Serde<Object, Date> dateSerde = (Serde<Object, Date>) serdesBuilder.build().get(Date.class);

        Date date = dateSerde.deserialize("2026-01-15T10:20Z");
        assertEquals(Instant.parse("2026-01-15T10:20:00Z"), date.toInstant());
    }

    @Test
    void testDeserializeLocalIsoWithoutOffsetDefaultsToUtc() {
        @SuppressWarnings("unchecked")
        Serde<Object, Date> dateSerde = (Serde<Object, Date>) serdesBuilder.build().get(Date.class);

        Date date = dateSerde.deserialize("2026-01-15T10:20:42");
        assertEquals(Instant.parse("2026-01-15T10:20:42Z"), date.toInstant());
    }

    @Test
    void testDeserializeEpochTimestampStringAndNumber() {
        @SuppressWarnings("unchecked")
        Serde<Object, Date> dateSerde = (Serde<Object, Date>) serdesBuilder.build().get(Date.class);

        long epochMillis = 1768472442000L;
        Date dateFromNumber = dateSerde.deserialize(epochMillis);
        assertEquals(epochMillis, dateFromNumber.getTime());

        Date dateFromString = dateSerde.deserialize(String.valueOf(epochMillis));
        assertEquals(epochMillis, dateFromString.getTime());
    }

    @Test
    void testDeserializeDateInstanceAndNullHandling() {
        @SuppressWarnings("unchecked")
        Serde<Object, Date> dateSerde = (Serde<Object, Date>) serdesBuilder.build().get(Date.class);

        Date original = new Date(1768472442000L);
        assertSame(original, dateSerde.deserialize(original));

        assertNull(dateSerde.deserialize(null));
        assertNull(dateSerde.deserialize("   "));
        assertNull(dateSerde.serialize(null));
    }

    @Test
    void testDeserializeInvalidThrowsIllegalArgumentException() {
        @SuppressWarnings("unchecked")
        Serde<Object, Date> dateSerde = (Serde<Object, Date>) serdesBuilder.build().get(Date.class);

        assertThrows(IllegalArgumentException.class, () -> dateSerde.deserialize("invalid-date-string"));
    }

    @Test
    void testSqlTypesSubtypeRegistration() {
        var serdes = serdesBuilder.build();

        @SuppressWarnings("unchecked")
        Serde<Object, java.sql.Timestamp> timestampSerde = (Serde<Object, java.sql.Timestamp>) serdes.get(java.sql.Timestamp.class);
        assertNotNull(timestampSerde);
        java.sql.Timestamp timestamp = timestampSerde.deserialize("2026-01-15T10:20:42Z");
        assertEquals(Instant.parse("2026-01-15T10:20:42Z"), timestamp.toInstant());
        assertEquals("2026-01-15T10:20:42Z", timestampSerde.serialize(timestamp));

        @SuppressWarnings("unchecked")
        Serde<Object, java.sql.Date> sqlDateSerde = (Serde<Object, java.sql.Date>) serdes.get(java.sql.Date.class);
        assertNotNull(sqlDateSerde);
        java.sql.Date sqlDate = sqlDateSerde.deserialize("2026-01-15T10:20:42Z");
        assertNotNull(sqlDate);

        @SuppressWarnings("unchecked")
        Serde<Object, java.sql.Time> sqlTimeSerde = (Serde<Object, java.sql.Time>) serdes.get(java.sql.Time.class);
        assertNotNull(sqlTimeSerde);
        java.sql.Time sqlTime = sqlTimeSerde.deserialize("2026-01-15T10:20:42Z");
        assertNotNull(sqlTime);
    }
}
