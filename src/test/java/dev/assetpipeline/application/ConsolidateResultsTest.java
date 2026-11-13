package dev.assetpipeline.application;

import static dev.assetpipeline.domain.AggregationStatus.COMPLETED;
import static dev.assetpipeline.domain.AggregationStatus.DEGRADED;
import static dev.assetpipeline.domain.AggregationStatus.FAILED;
import static dev.assetpipeline.domain.ProviderId.ALPHA_VANTAGE;
import static dev.assetpipeline.domain.ProviderId.FINNHUB;
import static dev.assetpipeline.domain.ProviderId.TWELVE_DATA;
import static dev.assetpipeline.domain.ProviderOutcome.OK;
import static org.assertj.core.api.Assertions.assertThat;

import dev.assetpipeline.domain.AggregatedAsset;
import dev.assetpipeline.domain.AggregationResult;
import dev.assetpipeline.domain.Fundamentals;
import dev.assetpipeline.domain.ProviderId;
import dev.assetpipeline.domain.ProviderOutcome;
import dev.assetpipeline.domain.ProviderSnapshot;
import dev.assetpipeline.domain.Quote;
import dev.assetpipeline.support.InMemoryResultStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsolidateResultsTest {

    private static final String REQUEST = "req-1";
    private static final Instant AS_OF = Instant.parse("2026-11-13T10:00:00Z");

    private final InMemoryResultStore store = new InMemoryResultStore();
    private final ConsolidateResults consolidate = new ConsolidateResults(store);

    private static Quote quote(String symbol, String price) {
        return new Quote(symbol, new BigDecimal(price), "USD", AS_OF);
    }

    private static Fundamentals fundamentals(String symbol, String peRatio) {
        return new Fundamentals(symbol, null, new BigDecimal(peRatio), null);
    }

    private void provide(ProviderId provider, String symbol, String price, String peRatio) {
        store.saveRaw(REQUEST, new ProviderSnapshot(
                provider,
                Map.of(symbol, quote(symbol, price)),
                Map.of(symbol, fundamentals(symbol, peRatio))));
    }

    private AggregatedAsset only(AggregationResult result) {
        assertThat(result.assets()).hasSize(1);
        return result.assets().get(0);
    }

    @Test
    void priceIsMedianOfAvailableProviders() {
        provide(FINNHUB, "AAPL", "100", "20");
        provide(ALPHA_VANTAGE, "AAPL", "102", "21");
        provide(TWELVE_DATA, "AAPL", "110", "22");
        var outcomes = Map.of(FINNHUB, OK, ALPHA_VANTAGE, OK, TWELVE_DATA, OK);

        var result = consolidate.execute(REQUEST, List.of("AAPL"), outcomes);

        assertThat(only(result).medianPrice()).isEqualByComparingTo("102");
        assertThat(only(result).sources()).containsExactly(FINNHUB, ALPHA_VANTAGE, TWELVE_DATA);
    }

    @Test
    void medianOfTwoProvidersIsTheirAverage() {
        provide(FINNHUB, "AAPL", "100", "20");
        provide(ALPHA_VANTAGE, "AAPL", "102", "21");
        var outcomes = Map.of(FINNHUB, OK, ALPHA_VANTAGE, OK, TWELVE_DATA, ProviderOutcome.FAILED);

        var result = consolidate.execute(REQUEST, List.of("AAPL"), outcomes);

        assertThat(only(result).medianPrice()).isEqualByComparingTo("101");
    }

    @Test
    void fundamentalsComeFromFirstProviderInPriorityOrder() {
        provide(TWELVE_DATA, "AAPL", "101", "33");
        provide(FINNHUB, "AAPL", "100", "20");
        var outcomes = Map.of(FINNHUB, OK, ALPHA_VANTAGE, ProviderOutcome.FAILED, TWELVE_DATA, OK);

        var result = consolidate.execute(REQUEST, List.of("AAPL"), outcomes);

        assertThat(only(result).fundamentals().peRatio()).isEqualByComparingTo("20");
    }

    @Test
    void allOkIsCompleted() {
        provide(FINNHUB, "AAPL", "100", "20");
        provide(ALPHA_VANTAGE, "AAPL", "100", "20");
        provide(TWELVE_DATA, "AAPL", "100", "20");
        var outcomes = Map.of(FINNHUB, OK, ALPHA_VANTAGE, OK, TWELVE_DATA, OK);

        var result = consolidate.execute(REQUEST, List.of("AAPL"), outcomes);

        assertThat(result.status()).isEqualTo(COMPLETED);
    }

    @Test
    void someFailedOrTimedOutIsDegraded() {
        provide(FINNHUB, "AAPL", "100", "20");
        var outcomes = Map.of(
                FINNHUB, OK,
                ALPHA_VANTAGE, ProviderOutcome.FAILED,
                TWELVE_DATA, ProviderOutcome.TIMEOUT);

        var result = consolidate.execute(REQUEST, List.of("AAPL"), outcomes);

        assertThat(result.status()).isEqualTo(DEGRADED);
        assertThat(result.providers()).containsEntry(ALPHA_VANTAGE, ProviderOutcome.FAILED)
                .containsEntry(TWELVE_DATA, ProviderOutcome.TIMEOUT);
    }

    @Test
    void noneOkIsFailedWithNoAssets() {
        var outcomes = Map.of(
                FINNHUB, ProviderOutcome.FAILED,
                ALPHA_VANTAGE, ProviderOutcome.TIMEOUT,
                TWELVE_DATA, ProviderOutcome.FAILED);

        var result = consolidate.execute(REQUEST, List.of("AAPL"), outcomes);

        assertThat(result.status()).isEqualTo(FAILED);
        assertThat(result.assets()).isEmpty();
    }

    @Test
    void symbolMissingFromEveryProviderIsOmitted() {
        provide(FINNHUB, "AAPL", "100", "20");
        var outcomes = Map.of(FINNHUB, OK, ALPHA_VANTAGE, OK, TWELVE_DATA, OK);
        store.saveRaw(REQUEST, new ProviderSnapshot(ALPHA_VANTAGE, Map.of(), Map.of()));
        store.saveRaw(REQUEST, new ProviderSnapshot(TWELVE_DATA, Map.of(), Map.of()));

        var result = consolidate.execute(REQUEST, List.of("AAPL", "ZZZZ"), outcomes);

        assertThat(result.assets()).extracting(AggregatedAsset::symbol).containsExactly("AAPL");
    }

    @Test
    void okProviderWithExpiredRawCountsAsFailed() {
        provide(TWELVE_DATA, "AAPL", "100", "20");
        var outcomes = Map.of(FINNHUB, OK, ALPHA_VANTAGE, OK, TWELVE_DATA, OK);
        store.saveRaw(REQUEST, new ProviderSnapshot(ALPHA_VANTAGE, Map.of("AAPL", quote("AAPL", "104")), Map.of()));

        var result = consolidate.execute(REQUEST, List.of("AAPL"), outcomes);

        assertThat(result.providers()).containsEntry(FINNHUB, ProviderOutcome.FAILED);
        assertThat(result.status()).isEqualTo(DEGRADED);
        assertThat(only(result).sources()).containsExactly(ALPHA_VANTAGE, TWELVE_DATA);
    }

    @Test
    void resultIsSavedToStore() {
        provide(FINNHUB, "AAPL", "100", "20");
        var outcomes = Map.of(FINNHUB, OK, ALPHA_VANTAGE, ProviderOutcome.FAILED, TWELVE_DATA, ProviderOutcome.FAILED);

        var result = consolidate.execute(REQUEST, List.of("AAPL"), outcomes);

        assertThat(store.findResult(REQUEST)).contains(result);
    }
}
