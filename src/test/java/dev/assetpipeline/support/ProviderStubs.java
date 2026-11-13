package dev.assetpipeline.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;

/** One WireMock server standing in for all three providers, each under its own path prefix. */
public class ProviderStubs {

    private static final String KEY = "test-key";

    private final WireMockServer server = new WireMockServer(options().dynamicPort());

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop();
    }

    public void reset() {
        server.resetAll();
    }

    public String finnhubUrl() {
        return server.baseUrl() + "/finnhub";
    }

    public String alphaVantageUrl() {
        return server.baseUrl() + "/alpha-vantage";
    }

    public String twelveDataUrl() {
        return server.baseUrl() + "/twelve-data";
    }

    public void finnhubOk(String symbol) {
        server.stubFor(finnhubQuote(symbol).willReturn(aResponse().withBody(ProviderSamples.read("finnhub/quote.json"))));
        stubFinnhubMetric(symbol);
    }

    public void finnhubFailsOnceWith503ThenOk(String symbol) {
        server.stubFor(finnhubQuote(symbol)
                .inScenario("finnhub-retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        server.stubFor(finnhubQuote(symbol)
                .inScenario("finnhub-retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withBody(ProviderSamples.read("finnhub/quote.json"))));
        stubFinnhubMetric(symbol);
    }

    public void finnhubAlwaysStatus(int status) {
        server.stubFor(get(urlPathEqualTo("/finnhub/quote")).willReturn(aResponse().withStatus(status)));
    }

    public int finnhubQuoteRequests() {
        return server.countRequestsMatching(getRequestedFor(urlPathEqualTo("/finnhub/quote")).build()).getCount();
    }

    public void alphaVantageOk(String symbol) {
        server.stubFor(alphaVantage("GLOBAL_QUOTE", symbol)
                .willReturn(aResponse().withBody(ProviderSamples.read("alpha-vantage/quote.json"))));
        server.stubFor(alphaVantage("OVERVIEW", symbol)
                .willReturn(aResponse().withBody(ProviderSamples.read("alpha-vantage/overview.json"))));
    }

    public void alphaVantageAlwaysStatus(int status) {
        server.stubFor(get(urlPathEqualTo("/alpha-vantage/query")).willReturn(aResponse().withStatus(status)));
    }

    public void twelveDataOk(String symbol) {
        server.stubFor(twelveData(symbol).willReturn(aResponse().withBody(ProviderSamples.read("twelve-data/quote.json"))));
    }

    public void twelveDataSlow(String symbol, Duration delay) {
        server.stubFor(twelveData(symbol).willReturn(aResponse()
                .withBody(ProviderSamples.read("twelve-data/quote.json"))
                .withFixedDelay((int) delay.toMillis())));
    }

    public void twelveDataAlwaysStatus(int status) {
        server.stubFor(get(urlPathEqualTo("/twelve-data/quote")).willReturn(aResponse().withStatus(status)));
    }

    private static MappingBuilder finnhubQuote(String symbol) {
        return get(urlPathEqualTo("/finnhub/quote"))
                .withQueryParam("symbol", equalTo(symbol))
                .withQueryParam("token", equalTo(KEY));
    }

    private void stubFinnhubMetric(String symbol) {
        server.stubFor(get(urlPathEqualTo("/finnhub/stock/metric"))
                .withQueryParam("symbol", equalTo(symbol))
                .withQueryParam("token", equalTo(KEY))
                .willReturn(aResponse().withBody(ProviderSamples.read("finnhub/metric.json"))));
    }

    private static MappingBuilder alphaVantage(String function, String symbol) {
        return get(urlPathEqualTo("/alpha-vantage/query"))
                .withQueryParam("function", equalTo(function))
                .withQueryParam("symbol", equalTo(symbol))
                .withQueryParam("apikey", equalTo(KEY));
    }

    private static MappingBuilder twelveData(String symbol) {
        return get(urlPathEqualTo("/twelve-data/quote"))
                .withQueryParam("symbol", equalTo(symbol))
                .withQueryParam("apikey", equalTo(KEY));
    }
}
