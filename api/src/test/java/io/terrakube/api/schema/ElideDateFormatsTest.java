package io.terrakube.api.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.yahoo.elide.Serdes;
import com.yahoo.elide.core.utils.coerce.converters.Serde;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ElideDateFormatsTest {

    @Test
    void secondPrecisionUtcSerdeIsRfc3339Seconds() {
        Serdes serdes = new Serdes();
        serdes.put(Date.class, serializing((Date d) -> d.toInstant().truncatedTo(ChronoUnit.SECONDS).toString()));
        assertThat(new ElideDateFormats(serdes).formatId(Date.class)).contains("rfc3339-seconds-utc");
    }

    @Test
    void fractionKeepingUtcSerdeIsRfc3339Fraction() {
        Serdes serdes = new Serdes();
        serdes.put(Instant.class, serializing(Instant::toString));
        assertThat(new ElideDateFormats(serdes).formatId(Instant.class)).contains("rfc3339-fraction-utc");
    }

    @Test
    void millisecondTruncatingSerdeIsUnknown() {
        Serdes serdes = new Serdes();
        serdes.put(Instant.class, serializing((Instant i) -> i.truncatedTo(ChronoUnit.MILLIS).toString()));
        assertThat(new ElideDateFormats(serdes).formatId(Instant.class)).isEmpty();
    }

    @Test
    void nonUtcOutputIsUnknown() {
        Serdes serdes = new Serdes();
        serdes.put(OffsetDateTime.class,
                serializing((OffsetDateTime d) -> d.withOffsetSameInstant(ZoneOffset.ofHours(2)).toString()));
        assertThat(new ElideDateFormats(serdes).formatId(OffsetDateTime.class)).isEmpty();
    }

    @Test
    void minutePrecisionIsUnknown() {
        Serdes serdes = new Serdes();
        serdes.put(Date.class, serializing((Date d) -> "2026-11-19T15:28Z"));
        assertThat(new ElideDateFormats(serdes).formatId(Date.class)).isEmpty();
    }

    @Test
    void unregisteredClassIsUnknown() {
        assertThat(new ElideDateFormats(new Serdes()).formatId(Date.class)).isEmpty();
    }

    @Test
    void definesEveryIdItCanReturn() {
        assertThat(new ElideDateFormats(new Serdes()).definitions())
                .containsOnlyKeys("rfc3339-fraction-utc", "rfc3339-seconds-utc");
    }

    private static <T> Serde<Object, T> serializing(Function<T, String> format) {
        return new Serde<>() {
            @Override
            public T deserialize(Object val) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object serialize(T val) {
                return format.apply(val);
            }
        };
    }
}
