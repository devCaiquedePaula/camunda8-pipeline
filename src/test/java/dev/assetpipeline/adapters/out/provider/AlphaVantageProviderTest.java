package dev.assetpipeline.adapters.out.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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

class AlphaVantageProviderTest {

    @RegisterExtension
    static final WireMockExtension wiremock = WireMockExtension.newInstance().build();

    private final AlphaVantageProvider provider =
            new AlphaVantageProvider(wiremock.baseUrl(), "test-key", Duration.ofSeconds(2));

    private void stub(String function, String symbol, int status, String body) {
        wiremock.stubFor(get(urlPathEqualTo("/query"))
                .withQueryParam("function", equalTo(function))
                .withQueryParam("symbol", equalTo(symbol))
                .withQueryParam("apikey", equalTo("test-key"))
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    @Test
    void mapsQuoteAndFundamentalsForAllSymbols() {
        stub("GLOBAL_QUOTE", "IBM", 200, ProviderSamples.read("alpha-vantage/quote.json"));
        stub("OVERVIEW", "IBM", 200, ProviderSamples.read("alpha-vantage/overview.json"));

        var snapshot = provider.fetch(List.of("IBM"));

        assertThat(provider.id()).isEqualTo(ProviderId.ALPHA_VANTAGE);
        var quote = snapshot.quotes().get("IBM");
        assertThat(quote.price()).isEqualByComparingTo("244.67");
        assertThat(quote.currency()).isEqualTo("USD");
        assertThat(quote.asOf()).isEqualTo(Instant.parse("2026-11-12T00:00:00Z"));
        var fundamentals = snapshot.fundamentals().get("IBM");
        assertThat(fundamentals.peRatio()).isEqualByComparingTo("21.18");
        assertThat(fundamentals.dividendYield()).isEqualByComparingTo("0.0276");
        assertThat(fundamentals.marketCap()).isEqualByComparingTo("234597892000");
    }

    @Test
    void http429IsTransient() {
        stub("GLOBAL_QUOTE", "IBM", 429, "");

        assertThatThrownBy(() -> provider.fetch(List.of("IBM"))).isInstanceOf(TransientProviderException.class);
    }

    @Test
    void http503IsTransient() {
        stub("GLOBAL_QUOTE", "IBM", 503, "");

        assertThatThrownBy(() -> provider.fetch(List.of("IBM"))).isInstanceOf(TransientProviderException.class);
    }

    @Test
    void http401IsBusiness() {
        stub("GLOBAL_QUOTE", "IBM", 401, "");

        assertThatThrownBy(() -> provider.fetch(List.of("IBM"))).isInstanceOf(BusinessProviderException.class);
    }

    @Test
    void rateLimitNoteWithHttp200IsTransient() {
        stub("GLOBAL_QUOTE", "IBM", 200,
                "{\"Information\":\"Thank you for using Alpha Vantage! Our standard API rate limit is 25 requests per day.\"}");

        assertThatThrownBy(() -> provider.fetch(List.of("IBM"))).isInstanceOf(TransientProviderException.class);
    }

    @Test
    void unknownSymbolIsBusinessError() {
        stub("GLOBAL_QUOTE", "ZZZZ", 200, "{\"Global Quote\":{}}");

        assertThatThrownBy(() -> provider.fetch(List.of("ZZZZ"))).isInstanceOf(BusinessProviderException.class);
    }

    @Test
    void partiallyUnknownSymbolsAreOmitted() {
        stub("GLOBAL_QUOTE", "IBM", 200, ProviderSamples.read("alpha-vantage/quote.json"));
        stub("OVERVIEW", "IBM", 200, ProviderSamples.read("alpha-vantage/overview.json"));
        stub("GLOBAL_QUOTE", "ZZZZ", 200, "{\"Global Quote\":{}}");

        var snapshot = provider.fetch(List.of("IBM", "ZZZZ"));

        assertThat(snapshot.quotes()).containsOnlyKeys("IBM");
    }
}
