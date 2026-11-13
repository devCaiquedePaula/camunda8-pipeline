package dev.assetpipeline.adapters.out.provider;

import com.fasterxml.jackson.databind.JsonNode;
import dev.assetpipeline.domain.BusinessProviderException;
import dev.assetpipeline.domain.Fundamentals;
import dev.assetpipeline.domain.MarketDataProvider;
import dev.assetpipeline.domain.ProviderId;
import dev.assetpipeline.domain.ProviderSnapshot;
import dev.assetpipeline.domain.Quote;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Finnhub: quote from /quote, valuation from /stock/metric. The free plan quotes in USD. */
@Component
public class FinnhubProvider implements MarketDataProvider {

    private static final String CURRENCY = "USD";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final ProviderHttp http;
    private final String apiKey;

    public FinnhubProvider(
            @Value("${assetpipeline.providers.finnhub.base-url}") String baseUrl,
            @Value("${assetpipeline.providers.finnhub.api-key}") String apiKey,
            @Value("${assetpipeline.http-timeout:PT5S}") Duration timeout) {
        this.http = new ProviderHttp("Finnhub", baseUrl, timeout);
        this.apiKey = apiKey;
    }

    @Override
    public ProviderId id() {
        return ProviderId.FINNHUB;
    }

    @Override
    public ProviderSnapshot fetch(List<String> symbols) {
        Map<String, Quote> quotes = new LinkedHashMap<>();
        Map<String, Fundamentals> fundamentals = new LinkedHashMap<>();
        for (String symbol : symbols) {
            Quote quote = quote(symbol);
            if (quote == null) {
                continue;
            }
            quotes.put(symbol, quote);
            fundamentals.put(symbol, fundamentals(symbol));
        }
        if (quotes.isEmpty()) {
            throw new BusinessProviderException("Finnhub does not know any of the requested symbols");
        }
        return new ProviderSnapshot(id(), quotes, fundamentals);
    }

    private Quote quote(String symbol) {
        var response = http.get("/quote", Map.of("symbol", symbol, "token", apiKey));
        JsonNode body = response.body();
        BigDecimal price = JsonValues.decimal(body.get("c"));
        long timestamp = body.path("t").asLong(0);
        if (response.status() != 200 || price == null || price.signum() == 0 || timestamp == 0) {
            return null;
        }
        return new Quote(symbol, price, CURRENCY, Instant.ofEpochSecond(timestamp));
    }

    private Fundamentals fundamentals(String symbol) {
        var response = http.get("/stock/metric", Map.of("symbol", symbol, "metric", "all", "token", apiKey));
        JsonNode metric = response.body().path("metric");
        BigDecimal dividendPercent = JsonValues.decimal(metric.get("dividendYieldIndicatedAnnual"));
        BigDecimal marketCapMillions = JsonValues.decimal(metric.get("marketCapitalization"));
        return new Fundamentals(
                symbol,
                marketCapMillions == null ? null : marketCapMillions.multiply(ONE_MILLION),
                JsonValues.decimal(metric.get("peTTM")),
                dividendPercent == null ? null : dividendPercent.divide(ONE_HUNDRED));
    }
}
