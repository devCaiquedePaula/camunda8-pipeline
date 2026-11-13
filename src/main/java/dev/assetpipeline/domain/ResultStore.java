package dev.assetpipeline.domain;

import java.util.Optional;

public interface ResultStore {

    void saveRaw(String requestId, ProviderSnapshot snapshot);

    Optional<ProviderSnapshot> loadRaw(String requestId, ProviderId provider);

    void saveResult(AggregationResult result);

    Optional<AggregationResult> findResult(String requestId);

    /** Removes the result and all raw snapshots of the request. */
    void delete(String requestId);
}
