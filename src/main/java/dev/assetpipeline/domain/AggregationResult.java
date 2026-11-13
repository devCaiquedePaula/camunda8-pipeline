package dev.assetpipeline.domain;

import java.util.List;
import java.util.Map;

public record AggregationResult(
        String requestId,
        AggregationStatus status,
        Map<ProviderId, ProviderOutcome> providers,
        List<AggregatedAsset> assets) {
}
