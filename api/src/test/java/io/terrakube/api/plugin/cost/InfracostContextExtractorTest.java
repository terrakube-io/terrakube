package io.terrakube.api.plugin.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.storage.StorageTypeService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class InfracostContextExtractorTest {

    private final StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
    private final InfracostContextExtractor extractor = new InfracostContextExtractor(storageTypeService, new ObjectMapper());

    @Test
    void extractsValidInfracostSummary() {
        String contextJson = """
                {
                  "infracost": {
                    "status": "SUCCESS",
                    "currency": "USD",
                    "totalMonthlyCost": "165.20",
                    "pastTotalMonthlyCost": "120.00",
                    "diffTotalMonthlyCost": "45.20",
                    "shareUrl": "https://dashboard.infracost.io/share/abc",
                    "summary": {
                      "totalDetectedResources": 12,
                      "totalSupportedResources": 10,
                      "totalUnsupportedResources": 1,
                      "totalNoPriceResources": 1
                    }
                  }
                }
                """;
        when(storageTypeService.getContext(42)).thenReturn(contextJson);

        Optional<InfracostSummary> result = extractor.extract(42);

        assertThat(result).isPresent();
        InfracostSummary summary = result.get();
        assertThat(summary.isSuccess()).isTrue();
        assertThat(summary.totalMonthlyCost()).isEqualByComparingTo(new BigDecimal("165.20"));
        assertThat(summary.pastTotalMonthlyCost()).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(summary.diffTotalMonthlyCost()).isEqualByComparingTo(new BigDecimal("45.20"));
        assertThat(summary.unpricedResourceCount()).isEqualTo(2);
        assertThat(summary.formatTotalMonthlyCost()).isEqualTo("$165.20");
        assertThat(summary.formatMonthlyDelta()).isEqualTo("+$45.20 (+37.7%)");
        assertThat(summary.shareUrl()).isEqualTo("https://dashboard.infracost.io/share/abc");
    }

    @Test
    void extractsErrorStateWithoutThrowing() {
        when(storageTypeService.getContext(43)).thenReturn("""
                {
                  "infracost": {
                    "status": "ERROR",
                    "errorMessage": "Pricing API unreachable"
                  }
                }
                """);

        Optional<InfracostSummary> result = extractor.extract(43);

        assertThat(result).isPresent();
        assertThat(result.get().isError()).isTrue();
        assertThat(result.get().isSuccess()).isFalse();
        assertThat(result.get().errorMessage()).isEqualTo("Pricing API unreachable");
    }

    @Test
    void returnsEmptyOnStorageFailureOrMissingKey() {
        when(storageTypeService.getContext(44)).thenThrow(new RuntimeException("S3 down"));
        assertThat(extractor.extract(44)).isEmpty();

        when(storageTypeService.getContext(45)).thenReturn("{\"planStructuredOutput\":{}}");
        assertThat(extractor.extract(45)).isEmpty();
    }
}
