package dev.assetpipeline.adapters.out.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.assetpipeline.domain.BusinessProviderException;
import dev.assetpipeline.domain.ProviderId;
import dev.assetpipeline.domain.TransientProviderException;
import dev.assetpipeline.support.ProviderSamples;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class FinnhubProviderTest {

    @RegisterExtension
    static final WireMockExtension wiremock = WireMockExtension.newInstance().build();

    private final FinnhubProvider provider = new FinnhubProvider(wiremock.baseUrl(), "test-key", Duration.ofSeconds(2));

    private void stubQuote(String symbol, int status, String body) {
        wiremock.stubFor(get(urlPathEqualTo("/quote"))
                .withQueryParam("symbol", com.github.tomakehurst.wiremock.client.WireMock.equalTo(symbol))
                .withQueryParam("token", com.github.tomakehurst.wiremock.client.WireMock.equalTo("test-key"))
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    private void stubMetric(String symbol, String body) {
        wiremock.stubFor(get(urlPathEqualTo("/stock/metric"))
                .withQueryParam("symbol", com.github.tomakehurst.wiremock.client.WireMock.equalTo(symbol))
                .withQueryParam("metric", com.github.tomakehurst.wiremock.client.WireMock.equalTo("all"))
                .withQueryParam("token", com.github.tomakehurst.wiremock.client.WireMock.equalTo("test-key"))
                .willReturn(aResponse().withStatus(200).withBody(body)));
    }

    @Test
    void mapsQuoteAndFundamentalsForAllSymbols() {
        stubQuote("AAPL", 200, ProviderSamples.read("finnhub/quote.json"));
        stubMetric("AAPL", ProviderSamples.read("finnhub/metric.json"));

        var snapshot = provider.fetch(List.of("AAPL"));

        assertThat(provider.id()).isEqualTo(ProviderId.FINNHUB);
        assertThat(snapshot.provider()).isEqualTo(ProviderId.FINNHUB);
        var quote = snapshot.quotes().get("AAPL");
        assertThat(quote.price()).isEqualByComparingTo("189.25");
        assertThat(quote.currency()).isEqualTo("USD");
        assertThat(quote.asOf()).isEqualTo(Instant.ofEpochSecond(1794517140L));
        var fundamentals = snapshot.fundamentals().get("AAPL");
        assertThat(fundamentals.peRatio()).isEqualByComparingTo("28.4");
        assertThat(fundamentals.dividendYield()).isEqualByComparingTo("0.005");
        assertThat(fundamentals.marketCap()).isEqualByComparingTo("2950000000000");
    }

    @Test
    void http429IsTransient() {
        stubQuote("AAPL", 429, "{\"error\":\"API limit reached.\"}");

        assertThatThrownBy(() -> provider.fetch(List.of("AAPL"))).isInstanceOf(TransientProviderException.class);
    }

    @Test
    void http503IsTransient() {
        stubQuote("AAPL", 503, "");

        assertThatThrownBy(() -> provider.fetch(List.of("AAPL"))).isInstanceOf(TransientProviderException.class);
    }

    @Test
    void http401IsBusiness() {
        stubQuote("AAPL", 401, "{\"error\":\"Invalid API key.\"}");

        assertThatThrownBy(() -> provider.fetch(List.of("AAPL"))).isInstanceOf(BusinessProviderException.class);
    }

    @Test
    void unknownSymbolIsBusinessError() {
        stubQuote("ZZZZ", 200, ProviderSamples.read("finnhub/quote-unknown.json"));

        assertThatThrownBy(() -> provider.fetch(List.of("ZZZZ"))).isInstanceOf(BusinessProviderException.class);
    }

    @Test
    void partiallyUnknownSymbolsAreOmitted() {
        stubQuote("AAPL", 200, ProviderSamples.read("finnhub/quote.json"));
        stubMetric("AAPL", ProviderSamples.read("finnhub/metric.json"));
        stubQuote("ZZZZ", 200, ProviderSamples.read("finnhub/quote-unknown.json"));

        var snapshot = provider.fetch(List.of("AAPL", "ZZZZ"));

        assertThat(snapshot.quotes()).containsOnlyKeys("AAPL");
    }
}
