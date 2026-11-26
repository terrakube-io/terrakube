package io.terrakube.api.plugin.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InfracostContextExtractorTest {

    private final StorageTypeService storageTypeService = mock(StorageTypeService.class);
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

    @Test
    void selectsLatestStepScopedInfracostKeyBeforeLegacyKey() {
        UUID step1Id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID step2Id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Step step1 = new Step();
        step1.setId(step1Id);
        step1.setStepNumber(100);
        Step step2 = new Step();
        step2.setId(step2Id);
        step2.setStepNumber(200);

        String contextJson = """
                {
                  "infracost": { "status": "SUCCESS", "totalMonthlyCost": 10.00 },
                  "infracost/%s": { "status": "SUCCESS", "totalMonthlyCost": 55.00 },
                  "infracost/%s": { "status": "SUCCESS", "totalMonthlyCost": 99.50 }
                }
                """.formatted(step1Id, step2Id);
        when(storageTypeService.getContext(46)).thenReturn(contextJson);

        InfracostSummary withSteps = extractor.extract(46, List.of(step1, step2)).orElseThrow();
        assertThat(withSteps.totalMonthlyCost()).isEqualByComparingTo(new BigDecimal("99.50"));

        // Without step metadata, prefixed step key is still preferred over legacy flat key.
        InfracostSummary withoutSteps = extractor.extract(46).orElseThrow();
        assertThat(withoutSteps.totalMonthlyCost()).isEqualByComparingTo(new BigDecimal("99.50"));
    }

    @Test
    void rejectsUntrustedOrUnsafeShareUrls() {
        assertThat(InfracostContextExtractor.sanitizeShareUrl("https://dashboard.infracost.io/share/abc")).isNotNull();
        assertThat(InfracostContextExtractor.sanitizeShareUrl("https://app.infracost.io/org/share/1")).isNotNull();

        assertThat(InfracostContextExtractor.sanitizeShareUrl("http://dashboard.infracost.io/share/abc")).isNull();
        assertThat(InfracostContextExtractor.sanitizeShareUrl("javascript:alert(1)")).isNull();
        assertThat(InfracostContextExtractor.sanitizeShareUrl("https://evil.example.com/share/abc")).isNull();
        assertThat(InfracostContextExtractor.sanitizeShareUrl("https://user:pass@dashboard.infracost.io/share/abc")).isNull();
        assertThat(InfracostContextExtractor.sanitizeShareUrl("https://dashboard.infracost.io/share/x)[click](https://evil.com)")).isNull();
    }

    @Test
    void formatsNullCostAndDiffAsEmDash() {
        InfracostSummary partial = new InfracostSummary("SKIPPED", "USD", null, null, null, 0, 0, 0, 0, null, null);
        assertThat(partial.formatTotalMonthlyCost()).isEqualTo("—");
        assertThat(partial.formatPastMonthlyCost()).isEqualTo("—");
        assertThat(partial.formatMonthlyDelta()).isEqualTo("—");

        InfracostSummary negative = new InfracostSummary(
                "SUCCESS", "USD",
                new BigDecimal("80.00"), new BigDecimal("100.00"), new BigDecimal("-20.00"),
                2, 2, 0, 0, null, null);
        assertThat(negative.formatMonthlyDelta()).isEqualTo("-$20.00 (-20.0%)");
    }

    @Test
    void sanitizesUntrustedCurrencyValuesInContext() {
        when(storageTypeService.getContext(47)).thenReturn("""
                {
                  "infracost": {
                    "status": "SUCCESS",
                    "currency": "USD | [phish](https://evil.com) @everyone <@U12345>",
                    "totalMonthlyCost": 42.00
                  }
                }
                """);
        InfracostSummary injected = extractor.extract(47).orElseThrow();
        assertThat(injected.currencyOrDefault()).isEqualTo("USD");

        when(storageTypeService.getContext(48)).thenReturn("""
                {
                  "infracost": {
                    "status": "SUCCESS",
                    "currency": " eur ",
                    "totalMonthlyCost": 42.00
                  }
                }
                """);
        InfracostSummary validLower = extractor.extract(48).orElseThrow();
        assertThat(validLower.currencyOrDefault()).isEqualTo("EUR");

        assertThat(InfracostContextExtractor.sanitizeCurrency("SUPERLONGCURRENCY")).isEqualTo("USD");
        assertThat(InfracostContextExtractor.sanitizeCurrency("  ")).isEqualTo("USD");
        assertThat(InfracostContextExtractor.sanitizeCurrency(null)).isEqualTo("USD");
    }
}
