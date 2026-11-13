package dev.assetpipeline.adapters.out.provider;

import com.fasterxml.jackson.databind.JsonNode;
import dev.assetpipeline.domain.BusinessProviderException;
import dev.assetpipeline.domain.Fundamentals;
import dev.assetpipeline.domain.MarketDataProvider;
import dev.assetpipeline.domain.ProviderId;
import dev.assetpipeline.domain.ProviderSnapshot;
import dev.assetpipeline.domain.Quote;
import dev.assetpipeline.domain.TransientProviderException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Twelve Data: quotes only. Errors can arrive as an HTTP status or as HTTP 200 with a status=error body. */
@Component
public class TwelveDataProvider implements MarketDataProvider {

    private final ProviderHttp http;
    private final String apiKey;

    public TwelveDataProvider(
            @Value("${assetpipeline.providers.twelve-data.base-url}") String baseUrl,
            @Value("${assetpipeline.providers.twelve-data.api-key}") String apiKey,
            @Value("${assetpipeline.http-timeout:PT5S}") Duration timeout) {
        this.http = new ProviderHttp("Twelve Data", baseUrl, timeout);
        this.apiKey = apiKey;
    }

    @Override
    public ProviderId id() {
        return ProviderId.TWELVE_DATA;
    }

    @Override
    public ProviderSnapshot fetch(List<String> symbols) {
        Map<String, Quote> quotes = new LinkedHashMap<>();
        for (String symbol : symbols) {
            Quote quote = quote(symbol);
            if (quote != null) {
                quotes.put(symbol, quote);
            }
        }
        if (quotes.isEmpty()) {
            throw new BusinessProviderException("Twelve Data does not know any of the requested symbols");
        }
        return new ProviderSnapshot(id(), quotes, Map.<String, Fundamentals>of());
    }

    private Quote quote(String symbol) {
        var response = http.get("/quote", Map.of("symbol", symbol, "apikey", apiKey));
        JsonNode body = response.body();
        if (response.status() == 400 || response.status() == 404 || bodyCode(body) == 400 || bodyCode(body) == 404) {
            return null;
        }
        if (bodyCode(body) == 429) {
            throw new TransientProviderException("Twelve Data rate limit reached");
        }
        if ("error".equals(body.path("status").asText())) {
            throw new BusinessProviderException("Twelve Data rejected the request (code " + bodyCode(body) + ")");
        }
        BigDecimal price = JsonValues.decimal(body.get("close"));
        if (price == null) {
            return null;
        }
        long timestamp = body.path("timestamp").asLong(0);
        Instant asOf = timestamp == 0 ? Instant.now() : Instant.ofEpochSecond(timestamp);
        return new Quote(symbol, price, body.path("currency").asText("USD"), asOf);
    }

    private static int bodyCode(JsonNode body) {
        return "error".equals(body.path("status").asText()) ? body.path("code").asInt(0) : 0;
    }
}
