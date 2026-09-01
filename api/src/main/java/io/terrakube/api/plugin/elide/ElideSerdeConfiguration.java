package io.terrakube.api.plugin.elide;

import com.yahoo.elide.SerdesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

@Configuration
public class ElideSerdeConfiguration {

    /**
     * Elide's default date serde uses minute precision (yyyy-MM-dd'T'HH:mm'Z'), which
     * truncates the seconds of fields like createdDate and lastJobDate in GraphQL
     * responses. The UI then computes relative times from the top of the minute and
     * shows things like "42 seconds ago" for a job that was just triggered (issue
     * #2787). Serialize with second precision instead.
     */
    @Bean
    public SerdesBuilderCustomizer secondPrecisionDateSerdes() {
        return builder -> builder.withISO8601Dates("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"));
    }
}
