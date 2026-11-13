package dev.assetpipeline.process;

import static dev.assetpipeline.domain.AggregationStatus.COMPLETED;
import static dev.assetpipeline.domain.AggregationStatus.DEGRADED;
import static dev.assetpipeline.domain.AggregationStatus.FAILED;
import static dev.assetpipeline.domain.AggregationStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.assetpipeline.domain.AggregationResult;
import dev.assetpipeline.domain.ProviderId;
import dev.assetpipeline.domain.ProviderOutcome;
import dev.assetpipeline.domain.ResultStore;
import dev.assetpipeline.domain.WorkflowStarter;
import dev.assetpipeline.support.ProviderStubs;
import dev.assetpipeline.support.SharedContainers;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class AggregateAssetsProcessIT {

    static final ProviderStubs providers = new ProviderStubs();

    @BeforeAll
    static void startProviders() {
        providers.start();
    }

    @AfterAll
    static void stopProviders() {
        providers.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("camunda.client.grpc-address", SharedContainers.CAMUNDA::grpcAddress);
        registry.add("camunda.client.rest-address", SharedContainers.CAMUNDA::restAddress);
        registry.add("spring.data.redis.host", SharedContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> SharedContainers.REDIS.getMappedPort(6379));
        registry.add("assetpipeline.providers.finnhub.base-url", providers::finnhubUrl);
        registry.add("assetpipeline.providers.alpha-vantage.base-url", providers::alphaVantageUrl);
        registry.add("assetpipeline.providers.twelve-data.base-url", providers::twelveDataUrl);
        registry.add("assetpipeline.providers.finnhub.api-key", () -> "test-key");
        registry.add("assetpipeline.providers.alpha-vantage.api-key", () -> "test-key");
        registry.add("assetpipeline.providers.twelve-data.api-key", () -> "test-key");
        registry.add("assetpipeline.provider-timeout", () -> "PT3S");
        registry.add("assetpipeline.http-timeout", () -> "PT2S");
        registry.add("assetpipeline.retry-backoff", () -> "PT1S");
    }

    @Autowired
    WorkflowStarter starter;

    @Autowired
    ResultStore store;

    @BeforeEach
    void resetStubs() {
        providers.reset();
    }

    private AggregationResult runFor(String symbol) {
        var requestId = UUID.randomUUID().toString();
        starter.start(requestId, List.of(symbol));
        await().atMost(Duration.ofSeconds(60))
                .until(() -> store.findResult(requestId).filter(r -> r.status() != PENDING).isPresent());
        return store.findResult(requestId).orElseThrow();
    }

    @Test
    void allProvidersOkIsCompleted() {
        providers.finnhubOk("AAPL");
        providers.alphaVantageOk("AAPL");
        providers.twelveDataOk("AAPL");

        var result = runFor("AAPL");

        assertThat(result.status()).isEqualTo(COMPLETED);
        assertThat(result.providers()).containsOnly(
                java.util.Map.entry(ProviderId.FINNHUB, ProviderOutcome.OK),
                java.util.Map.entry(ProviderId.ALPHA_VANTAGE, ProviderOutcome.OK),
                java.util.Map.entry(ProviderId.TWELVE_DATA, ProviderOutcome.OK));
        assertThat(result.assets()).hasSize(1);
        assertThat(result.assets().get(0).medianPrice()).isEqualByComparingTo("244.67");
    }

    @Test
    void slowProviderTimesOutAndResultIsDegraded() {
        providers.finnhubOk("AAPL");
        providers.alphaVantageOk("AAPL");
        providers.twelveDataSlow("AAPL", Duration.ofSeconds(6));

        var result = runFor("AAPL");

        assertThat(result.status()).isEqualTo(DEGRADED);
        assertThat(result.providers()).containsEntry(ProviderId.TWELVE_DATA, ProviderOutcome.TIMEOUT);
    }

    @Test
    void invalidKeyOnOneProviderIsDegraded() {
        providers.finnhubOk("AAPL");
        providers.alphaVantageAlwaysStatus(401);
        providers.twelveDataOk("AAPL");

        var result = runFor("AAPL");

        assertThat(result.status()).isEqualTo(DEGRADED);
        assertThat(result.providers()).containsEntry(ProviderId.ALPHA_VANTAGE, ProviderOutcome.FAILED);
    }

    @Test
    void transientErrorIsRetriedThenSucceeds() {
        providers.finnhubFailsOnceWith503ThenOk("AAPL");
        providers.alphaVantageOk("AAPL");
        providers.twelveDataOk("AAPL");

        var result = runFor("AAPL");

        assertThat(result.status()).isEqualTo(COMPLETED);
        assertThat(providers.finnhubQuoteRequests()).isEqualTo(2);
    }

    @Test
    void allProvidersFailingIsFailed() {
        providers.finnhubAlwaysStatus(401);
        providers.alphaVantageAlwaysStatus(401);
        providers.twelveDataAlwaysStatus(401);

        var result = runFor("AAPL");

        assertThat(result.status()).isEqualTo(FAILED);
        assertThat(result.assets()).isEmpty();
    }
}
