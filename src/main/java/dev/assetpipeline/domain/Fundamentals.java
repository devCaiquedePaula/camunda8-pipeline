package dev.assetpipeline.domain;

import java.math.BigDecimal;

/** Valuation data for one symbol. Any field may be null when the provider does not supply it. */
public record Fundamentals(String symbol, BigDecimal marketCap, BigDecimal peRatio, BigDecimal dividendYield) {
}
