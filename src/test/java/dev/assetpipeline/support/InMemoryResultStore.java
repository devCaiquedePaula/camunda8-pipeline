package dev.assetpipeline.support;

import dev.assetpipeline.domain.AggregationResult;
import dev.assetpipeline.domain.ProviderId;
import dev.assetpipeline.domain.ProviderSnapshot;
import dev.assetpipeline.domain.ResultStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory fake of the {@link ResultStore} port for unit tests. */
public class InMemoryResultStore implements ResultStore {

    private final Map<String, ProviderSnapshot> raw = new HashMap<>();
    private final Map<String, AggregationResult> results = new HashMap<>();

    @Override
    public void saveRaw(String requestId, ProviderSnapshot snapshot) {
        raw.put(key(requestId, snapshot.provider()), snapshot);
    }

    @Override
    public Optional<ProviderSnapshot> loadRaw(String requestId, ProviderId provider) {
        return Optional.ofNullable(raw.get(key(requestId, provider)));
    }

    @Override
    public void saveResult(AggregationResult result) {
        results.put(result.requestId(), result);
    }

    @Override
    public Optional<AggregationResult> findResult(String requestId) {
        return Optional.ofNullable(results.get(requestId));
    }

    @Override
    public void delete(String requestId) {
        results.remove(requestId);
        raw.keySet().removeIf(k -> k.startsWith(requestId + "|"));
    }

    private static String key(String requestId, ProviderId provider) {
        return requestId + "|" + provider;
    }
}
