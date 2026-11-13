package dev.assetpipeline.domain;

/** Market data providers. Declaration order is the priority order used for fundamentals. */
public enum ProviderId {
    FINNHUB,
    ALPHA_VANTAGE,
    TWELVE_DATA
}
