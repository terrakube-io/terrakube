package io.terrakube.api.plugin.elide;

import com.yahoo.elide.SerdesBuilderCustomizer;
import com.yahoo.elide.core.utils.coerce.converters.Serde;
import org.apache.commons.lang3.time.FastDateFormat;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Function;

@Configuration
public class ElideSerdeConfiguration {

    private static final FastDateFormat OUTPUT_FORMAT =
            FastDateFormat.getInstance("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone(ZoneOffset.UTC));

    private static final DateTimeFormatter FALLBACK_PARSER = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral('T')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalEnd()
            .optionalStart()
            .appendOffsetId()
            .optionalEnd()
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .parseDefaulting(ChronoField.OFFSET_SECONDS, 0)
            .toFormatter(Locale.ROOT);

    /**
     * Elide's default date serde uses minute precision (yyyy-MM-dd'T'HH:mm'Z'), which
     * truncates the seconds of fields like createdDate and lastJobDate in GraphQL
     * and JSON:API responses. The UI then computes relative times from the top of the minute and
     * shows things like "42 seconds ago" for a job that was just triggered (issue #2787).
     *
     * This customizer registers second-precision serialization while maintaining robust, flexible
     * ISO-8601 deserialization (accepting milliseconds, timezone offsets, minute fallback, and epoch timestamps).
     */
    @Bean
    public SerdesBuilderCustomizer secondPrecisionDateSerdes() {
        return builder -> {
            builder.entry(Date.class, createDateSerde(Date.class, Function.identity()));
            builder.entry(java.sql.Date.class, createDateSerde(java.sql.Date.class, d -> new java.sql.Date(d.getTime())));
            builder.entry(java.sql.Timestamp.class, createDateSerde(java.sql.Timestamp.class, d -> new java.sql.Timestamp(d.getTime())));
            builder.entry(java.sql.Time.class, createDateSerde(java.sql.Time.class, d -> new java.sql.Time(d.getTime())));
        };
    }

    private static <T extends Date> Serde<Object, T> createDateSerde(Class<T> targetClass, Function<Date, T> converter) {
        return new Serde<>() {
            @Override
            public T deserialize(Object val) {
                Date date = parseDate(val);
                if (date == null) {
                    return null;
                }
                if (targetClass.isInstance(date)) {
                    return targetClass.cast(date);
                }
                return converter.apply(date);
            }

            @Override
            public Object serialize(T val) {
                if (val == null) {
                    return null;
                }
                return OUTPUT_FORMAT.format(val);
            }
        };
    }

    private static Date parseDate(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Date date) {
            return date;
        }
        if (val instanceof Number number) {
            return new Date(number.longValue());
        }
        String str = val.toString().trim();
        if (str.isEmpty()) {
            return null;
        }

        // 1. Try standard ISO_DATE_TIME parsing (supports Z, offsets, milliseconds)
        try {
            TemporalAccessor accessor = DateTimeFormatter.ISO_DATE_TIME.parse(str);
            if (accessor.isSupported(ChronoField.INSTANT_SECONDS)) {
                return Date.from(Instant.from(accessor));
            } else if (accessor.isSupported(ChronoField.OFFSET_SECONDS)) {
                return Date.from(OffsetDateTime.from(accessor).toInstant());
            } else {
                return Date.from(LocalDateTime.from(accessor).atZone(ZoneOffset.UTC).toInstant());
            }
        } catch (DateTimeParseException e) {
            // 2. Try minute precision fallback (e.g. 2026-01-15T10:20Z)
            try {
                TemporalAccessor fallbackAccessor = FALLBACK_PARSER.parse(str);
                return Date.from(Instant.from(fallbackAccessor));
            } catch (Exception ignored) {
            }

            // 3. Try epoch timestamp as numeric string
            try {
                return new Date(Long.parseLong(str));
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Cannot parse date: '" + str + "'", e);
            }
        }
    }
}
