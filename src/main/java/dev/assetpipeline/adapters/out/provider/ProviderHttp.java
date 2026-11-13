package dev.assetpipeline.adapters.out.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.assetpipeline.domain.BusinessProviderException;
import dev.assetpipeline.domain.TransientProviderException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Shared HTTP/JSON access for the provider adapters. Classifies transport-level failures only:
 * 429, 5xx, I/O errors and unreadable bodies are transient; 401 and 403 are business errors.
 * Any other status is handed back so the adapter can decide (for example 400 for an unknown symbol).
 * Exception messages never contain the request URL, because it carries the API key.
 */
class ProviderHttp {

    record Response(int status, JsonNode body) {
    }

    private record Raw(int status, String body) {
    }

    private final String provider;
    private final RestClient client;
    private final ObjectMapper json = new ObjectMapper();

    ProviderHttp(String provider, String baseUrl, Duration timeout) {
        this.provider = provider;
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    Response get(String path, Map<String, String> query) {
        Raw raw;
        try {
            raw = client.get()
                    .uri(uri -> {
                        uri.path(path);
                        query.forEach(uri::queryParam);
                        return uri.build();
                    })
                    .exchange((request, response) -> new Raw(
                            response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            throw new TransientProviderException(provider + " request failed: " + e.getClass().getSimpleName());
        }

        int status = raw.status();
        if (status == 429 || status >= 500) {
            throw new TransientProviderException(provider + " answered HTTP " + status);
        }
        if (status == 401 || status == 403) {
            throw new BusinessProviderException(provider + " rejected the credentials (HTTP " + status + ")");
        }
        return new Response(status, parse(raw.body()));
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new TransientProviderException(provider + " returned an unreadable body");
        }
    }
}
