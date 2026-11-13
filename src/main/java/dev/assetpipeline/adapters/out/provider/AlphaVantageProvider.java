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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Alpha Vantage: GLOBAL_QUOTE for the price, OVERVIEW for currency and valuation. The free plan reports quota
 * problems as HTTP 200 with a Note or Information field, which counts as transient.
 */
@Component
public class AlphaVantageProvider implements MarketDataProvider {

    private final ProviderHttp http;
    private final String apiKey;

    public AlphaVantageProvider(
            @Value("${assetpipeline.providers.alpha-vantage.base-url}") String baseUrl,
            @Value("${assetpipeline.providers.alpha-vantage.api-key}") String apiKey,
            @Value("${assetpipeline.http-timeout:PT5S}") Duration timeout) {
        this.http = new ProviderHttp("Alpha Vantage", baseUrl, timeout);
        this.apiKey = apiKey;
    }

    @Override
    public ProviderId id() {
        return ProviderId.ALPHA_VANTAGE;
    }

    @Override
    public ProviderSnapshot fetch(List<String> symbols) {
        Map<String, Quote> quotes = new LinkedHashMap<>();
        Map<String, Fundamentals> fundamentals = new LinkedHashMap<>();
        for (String symbol : symbols) {
            JsonNode quoteBody = call("GLOBAL_QUOTE", symbol);
            JsonNode globalQuote = quoteBody.path("Global Quote");
            BigDecimal price = JsonValues.decimal(globalQuote.get("05. price"));
            if (price == null) {
                continue;
            }
            JsonNode overview = call("OVERVIEW", symbol);
            String currency = overview.path("Currency").asText("USD");
            LocalDate tradingDay = LocalDate.parse(globalQuote.path("07. latest trading day").asText());
            quotes.put(symbol, new Quote(symbol, price, currency, tradingDay.atStartOfDay().toInstant(ZoneOffset.UTC)));
            fundamentals.put(symbol, new Fundamentals(
                    symbol,
                    JsonValues.decimal(overview.get("MarketCapitalization")),
                    JsonValues.decimal(overview.get("PERatio")),
                    JsonValues.decimal(overview.get("DividendYield"))));
        }
        if (quotes.isEmpty()) {
            throw new BusinessProviderException("Alpha Vantage does not know any of the requested symbols");
        }
        return new ProviderSnapshot(id(), quotes, fundamentals);
    }

    private JsonNode call(String function, String symbol) {
        var response = http.get("/query", Map.of("function", function, "symbol", symbol, "apikey", apiKey));
        JsonNode body = response.body();
        if (body.has("Note") || body.has("Information")) {
            throw new TransientProviderException("Alpha Vantage quota or rate limit reached");
        }
        return body;
    }
}
