package dev.assetpipeline.application;

import dev.assetpipeline.domain.AggregatedAsset;
import dev.assetpipeline.domain.AggregationResult;
import dev.assetpipeline.domain.AggregationStatus;
import dev.assetpipeline.domain.Fundamentals;
import dev.assetpipeline.domain.ProviderId;
import dev.assetpipeline.domain.ProviderOutcome;
import dev.assetpipeline.domain.ProviderSnapshot;
import dev.assetpipeline.domain.Quote;
import dev.assetpipeline.domain.ResultStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Merges provider snapshots into one result and stores it. Price is the median, fundamentals follow provider priority. */
public class ConsolidateResults {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final ResultStore store;

    public ConsolidateResults(ResultStore store) {
        this.store = store;
    }

    public AggregationResult execute(
            String requestId, List<String> symbols, Map<ProviderId, ProviderOutcome> outcomes) {
        Map<ProviderId, ProviderOutcome> effective = new EnumMap<>(ProviderId.class);
        Map<ProviderId, ProviderSnapshot> snapshots = new EnumMap<>(ProviderId.class);
        for (ProviderId provider : ProviderId.values()) {
            ProviderOutcome outcome = outcomes.getOrDefault(provider, ProviderOutcome.FAILED);
            if (outcome == ProviderOutcome.OK) {
                Optional<ProviderSnapshot> snapshot = store.loadRaw(requestId, provider);
                if (snapshot.isPresent()) {
                    snapshots.put(provider, snapshot.get());
                } else {
                    outcome = ProviderOutcome.FAILED;
                }
            }
            effective.put(provider, outcome);
        }

        AggregationResult result = new AggregationResult(
                requestId, statusOf(effective, snapshots), effective, assetsOf(symbols, snapshots));
        store.saveResult(result);
        return result;
    }

    private static AggregationStatus statusOf(
            Map<ProviderId, ProviderOutcome> effective, Map<ProviderId, ProviderSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return AggregationStatus.FAILED;
        }
        boolean allOk = effective.values().stream().allMatch(o -> o == ProviderOutcome.OK);
        return allOk ? AggregationStatus.COMPLETED : AggregationStatus.DEGRADED;
    }

    private static List<AggregatedAsset> assetsOf(
            List<String> symbols, Map<ProviderId, ProviderSnapshot> snapshots) {
        List<AggregatedAsset> assets = new ArrayList<>();
        for (String symbol : symbols) {
            List<ProviderId> sources = new ArrayList<>();
            List<BigDecimal> prices = new ArrayList<>();
            String currency = null;
            Fundamentals fundamentals = null;
            for (Map.Entry<ProviderId, ProviderSnapshot> entry : snapshots.entrySet()) {
                Quote quote = entry.getValue().quotes().get(symbol);
                if (quote == null) {
                    continue;
                }
                sources.add(entry.getKey());
                prices.add(quote.price());
                if (currency == null) {
                    currency = quote.currency();
                }
                if (fundamentals == null) {
                    fundamentals = entry.getValue().fundamentals().get(symbol);
                }
            }
            if (!sources.isEmpty()) {
                assets.add(new AggregatedAsset(symbol, median(prices), currency, fundamentals, sources));
            }
        }
        return assets;
    }

    private static BigDecimal median(List<BigDecimal> prices) {
        List<BigDecimal> sorted = prices.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        BigDecimal low = sorted.get(middle - 1);
        BigDecimal high = sorted.get(middle);
        return low.add(high).divide(TWO, Math.max(low.scale(), high.scale()) + 1, RoundingMode.HALF_UP);
    }
}
