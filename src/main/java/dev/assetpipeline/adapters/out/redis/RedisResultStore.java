package dev.assetpipeline.adapters.out.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.assetpipeline.domain.AggregationResult;
import dev.assetpipeline.domain.ProviderId;
import dev.assetpipeline.domain.ProviderSnapshot;
import dev.assetpipeline.domain.ResultStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Stores raw provider snapshots and consolidated results as JSON under agg:{requestId}:..., all with a TTL. */
@Component
public class RedisResultStore implements ResultStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public RedisResultStore(
            StringRedisTemplate redis, ObjectMapper json, @Value("${assetpipeline.result-ttl}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    @Override
    public void saveRaw(String requestId, ProviderSnapshot snapshot) {
        write(rawKey(requestId, snapshot.provider()), snapshot);
    }

    @Override
    public Optional<ProviderSnapshot> loadRaw(String requestId, ProviderId provider) {
        return read(rawKey(requestId, provider), ProviderSnapshot.class);
    }

    @Override
    public void saveResult(AggregationResult result) {
        write(resultKey(result.requestId()), result);
    }

    @Override
    public Optional<AggregationResult> findResult(String requestId) {
        return read(resultKey(requestId), AggregationResult.class);
    }

    @Override
    public void delete(String requestId) {
        List<String> keys = new ArrayList<>();
        keys.add(resultKey(requestId));
        for (ProviderId provider : ProviderId.values()) {
            keys.add(rawKey(requestId, provider));
        }
        redis.delete(keys);
    }

    private void write(String key, Object value) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize value for " + key, e);
        }
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        String value = redis.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value, type));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize value of " + key, e);
        }
    }

    private static String rawKey(String requestId, ProviderId provider) {
        return "agg:" + requestId + ":raw:" + provider.name();
    }

    private static String resultKey(String requestId) {
        return "agg:" + requestId + ":result";
    }
}
