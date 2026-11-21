package io.terrakube.api.plugin.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public record InfracostSummary(
        String status,
        String currency,
        BigDecimal totalMonthlyCost,
        BigDecimal pastTotalMonthlyCost,
        BigDecimal diffTotalMonthlyCost,
        int totalDetectedResources,
        int totalSupportedResources,
        int totalUnsupportedResources,
        int totalNoPriceResources,
        String shareUrl,
        String errorMessage) {

    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status);
    }

    public boolean isError() {
        return "ERROR".equalsIgnoreCase(status);
    }

    public String currencyOrDefault() {
        return (currency != null && !currency.isBlank()) ? currency.trim() : "USD";
    }

    public int unpricedResourceCount() {
        return Math.max(0, totalUnsupportedResources) + Math.max(0, totalNoPriceResources);
    }

    public String formatTotalMonthlyCost() {
        return formatMoney(totalMonthlyCost, currencyOrDefault());
    }

    public String formatPastMonthlyCost() {
        return formatMoney(pastTotalMonthlyCost, currencyOrDefault());
    }

    public String formatMonthlyDelta() {
        if (diffTotalMonthlyCost == null) {
            return formatMoney(BigDecimal.ZERO, currencyOrDefault());
        }
        String sign = diffTotalMonthlyCost.signum() > 0 ? "+" : (diffTotalMonthlyCost.signum() < 0 ? "-" : "");
        String formattedAbs = formatMoney(diffTotalMonthlyCost.abs(), currencyOrDefault());
        if (pastTotalMonthlyCost != null && pastTotalMonthlyCost.signum() > 0) {
            BigDecimal pct = diffTotalMonthlyCost
                    .multiply(BigDecimal.valueOf(100))
                    .divide(pastTotalMonthlyCost, 1, RoundingMode.HALF_UP);
            String pctSign = pct.signum() > 0 ? "+" : "";
            return String.format("%s%s (%s%s%%)", sign, formattedAbs, pctSign, pct.stripTrailingZeros().scale() <= 0
                    ? pct.setScale(1, RoundingMode.HALF_UP).toPlainString()
                    : pct.toPlainString());
        }
        return sign + formattedAbs;
    }

    public static String formatMoney(BigDecimal amount, String currencyCode) {
        BigDecimal safeAmount = amount != null ? amount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        DecimalFormat df = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        String code = (currencyCode != null && !currencyCode.isBlank()) ? currencyCode.trim().toUpperCase(Locale.ROOT) : "USD";
        String prefix = switch (code) {
            case "USD" -> "$";
            case "EUR" -> "€";
            case "GBP" -> "£";
            default -> code + " ";
        };
        return prefix + df.format(safeAmount);
    }
}
