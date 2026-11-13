package dev.assetpipeline.adapters.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.assetpipeline.domain.AggregatedAsset;
import dev.assetpipeline.domain.AggregationResult;
import dev.assetpipeline.domain.AggregationStatus;
import dev.assetpipeline.domain.Fundamentals;
import dev.assetpipeline.domain.ProviderId;
import dev.assetpipeline.domain.ProviderOutcome;
import dev.assetpipeline.domain.ProviderSnapshot;
import dev.assetpipeline.domain.Quote;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisResultStoreIT {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate template;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    private static RedisResultStore storeWithTtl(Duration ttl) {
        return new RedisResultStore(template, new ObjectMapper().findAndRegisterModules(), ttl);
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    private static ProviderSnapshot snapshot() {
        return new ProviderSnapshot(
                ProviderId.FINNHUB,
                Map.of("AAPL", new Quote("AAPL", new BigDecimal("189.25"), "USD", Instant.parse("2026-11-13T10:00:00Z"))),
                Map.of("AAPL", new Fundamentals("AAPL", null, new BigDecimal("28.4"), new BigDecimal("0.5"))));
    }

    private static AggregationResult result(String requestId) {
        return new AggregationResult(
                requestId,
                AggregationStatus.DEGRADED,
                Map.of(ProviderId.FINNHUB, ProviderOutcome.OK, ProviderId.ALPHA_VANTAGE, ProviderOutcome.TIMEOUT),
                List.of(new AggregatedAsset(
                        "AAPL", new BigDecimal("189.25"), "USD",
                        new Fundamentals("AAPL", null, new BigDecimal("28.4"), null),
                        List.of(ProviderId.FINNHUB))));
    }

    @Test
    void savedRawSnapshotIsLoadedBack() {
        var store = storeWithTtl(Duration.ofMinutes(5));
        var requestId = newRequestId();

        store.saveRaw(requestId, snapshot());

        assertThat(store.loadRaw(requestId, ProviderId.FINNHUB)).contains(snapshot());
        assertThat(store.loadRaw(requestId, ProviderId.TWELVE_DATA)).isEmpty();
    }

    @Test
    void savedResultIsFound() {
        var store = storeWithTtl(Duration.ofMinutes(5));
        var requestId = newRequestId();

        store.saveResult(result(requestId));

        assertThat(store.findResult(requestId)).contains(result(requestId));
    }

    @Test
    void unknownRequestIsEmpty() {
        var store = storeWithTtl(Duration.ofMinutes(5));

        assertThat(store.findResult(newRequestId())).isEmpty();
    }

    @Test
    void entriesExpireAfterTtl() {
        var store = storeWithTtl(Duration.ofSeconds(1));
        var requestId = newRequestId();
        store.saveResult(result(requestId));
        store.saveRaw(requestId, snapshot());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(store.findResult(requestId)).isEmpty();
            assertThat(store.loadRaw(requestId, ProviderId.FINNHUB)).isEmpty();
        });
    }

    @Test
    void deleteRemovesResultAndRaw() {
        var store = storeWithTtl(Duration.ofMinutes(5));
        var requestId = newRequestId();
        store.saveResult(result(requestId));
        store.saveRaw(requestId, snapshot());

        store.delete(requestId);

        assertThat(template.keys("agg:" + requestId + ":*")).isEmpty();
    }
}
