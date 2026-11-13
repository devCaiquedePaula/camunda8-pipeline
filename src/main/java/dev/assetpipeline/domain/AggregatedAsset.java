package dev.assetpipeline.domain;

import java.math.BigDecimal;
import java.util.List;

public record AggregatedAsset(
        String symbol,
        BigDecimal medianPrice,
        String currency,
        Fundamentals fundamentals,
        List<ProviderId> sources) {
}
