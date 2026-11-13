package dev.assetpipeline.domain;

import java.util.Map;

/** Everything one provider returned for a request, keyed by symbol. */
public record ProviderSnapshot(
        ProviderId provider,
        Map<String, Quote> quotes,
        Map<String, Fundamentals> fundamentals) {
}
