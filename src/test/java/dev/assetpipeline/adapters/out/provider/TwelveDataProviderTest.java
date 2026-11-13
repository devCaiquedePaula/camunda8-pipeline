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

class TwelveDataProviderTest {

    private static final String SYMBOL_NOT_FOUND =
            "{\"code\":400,\"message\":\"**symbol** not found: ZZZZ.\",\"status\":\"error\"}";

    @RegisterExtension
    static final WireMockExtension wiremock = WireMockExtension.newInstance().build();

    private final TwelveDataProvider provider =
            new TwelveDataProvider(wiremock.baseUrl(), "test-key", Duration.ofSeconds(2));

    private void stubQuote(String symbol, int status, String body) {
        wiremock.stubFor(get(urlPathEqualTo("/quote"))
                .withQueryParam("symbol", equalTo(symbol))
                .withQueryParam("apikey", equalTo("test-key"))
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    @Test
    void mapsQuoteForAllSymbolsWithoutFundamentals() {
        stubQuote("AAPL", 200, ProviderSamples.read("twelve-data/quote.json"));

        var snapshot = provider.fetch(List.of("AAPL"));

        assertThat(provider.id()).isEqualTo(ProviderId.TWELVE_DATA);
        var quote = snapshot.quotes().get("AAPL");
        assertThat(quote.price()).isEqualByComparingTo("354.88");
        assertThat(quote.currency()).isEqualTo("USD");
        assertThat(quote.asOf()).isEqualTo(Instant.ofEpochSecond(1794493800L));
        assertThat(snapshot.fundamentals()).isEmpty();
    }

    @Test
    void http429IsTransient() {
        stubQuote("AAPL", 429, "");

        assertThatThrownBy(() -> provider.fetch(List.of("AAPL"))).isInstanceOf(TransientProviderException.class);
    }

    @Test
    void http503IsTransient() {
        stubQuote("AAPL", 503, "");

        assertThatThrownBy(() -> provider.fetch(List.of("AAPL"))).isInstanceOf(TransientProviderException.class);
    }

    @Test
    void http401IsBusiness() {
        stubQuote("AAPL", 401, "{\"code\":401,\"message\":\"apikey parameter is incorrect\",\"status\":\"error\"}");

        assertThatThrownBy(() -> provider.fetch(List.of("AAPL"))).isInstanceOf(BusinessProviderException.class);
    }

    @Test
    void rateLimitErrorBodyWithHttp200IsTransient() {
        stubQuote("AAPL", 200, "{\"code\":429,\"message\":\"You have run out of API credits\",\"status\":\"error\"}");

        assertThatThrownBy(() -> provider.fetch(List.of("AAPL"))).isInstanceOf(TransientProviderException.class);
    }

    @Test
    void unknownSymbolIsBusinessError() {
        stubQuote("ZZZZ", 400, SYMBOL_NOT_FOUND);

        assertThatThrownBy(() -> provider.fetch(List.of("ZZZZ"))).isInstanceOf(BusinessProviderException.class);
    }

    @Test
    void partiallyUnknownSymbolsAreOmitted() {
        stubQuote("AAPL", 200, ProviderSamples.read("twelve-data/quote.json"));
        stubQuote("ZZZZ", 400, SYMBOL_NOT_FOUND);

        var snapshot = provider.fetch(List.of("AAPL", "ZZZZ"));

        assertThat(snapshot.quotes()).containsOnlyKeys("AAPL");
    }
}
