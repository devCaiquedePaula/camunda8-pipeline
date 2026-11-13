package dev.assetpipeline.adapters.in.worker;

import dev.assetpipeline.domain.BusinessProviderException;
import dev.assetpipeline.domain.MarketDataProvider;
import dev.assetpipeline.domain.ProviderId;
import dev.assetpipeline.domain.ProviderOutcome;
import dev.assetpipeline.domain.ResultStore;
import dev.assetpipeline.domain.TransientProviderException;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.exception.BpmnError;
import io.camunda.client.exception.JobError;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One job worker per provider branch of the aggregate-assets process. Thin by design: transient failures fail the
 * job so Zeebe retries it with a backoff, business failures become the PROVIDER_REJECTED BPMN error.
 */
@Component
public class ProviderWorkers {

    static final String REJECTED_ERROR_CODE = "PROVIDER_REJECTED";

    private final Map<ProviderId, MarketDataProvider> providers = new EnumMap<>(ProviderId.class);
    private final ResultStore store;
    private final Duration retryBackoff;

    public ProviderWorkers(
            List<MarketDataProvider> providers,
            ResultStore store,
            @Value("${assetpipeline.retry-backoff:PT2S}") Duration retryBackoff) {
        providers.forEach(provider -> this.providers.put(provider.id(), provider));
        this.store = store;
        this.retryBackoff = retryBackoff;
    }

    @JobWorker(type = "fetch-finnhub")
    public Map<String, Object> finnhub(
            ActivatedJob job, @Variable String requestId, @Variable List<String> symbols) {
        return fetch(ProviderId.FINNHUB, job, requestId, symbols);
    }

    @JobWorker(type = "fetch-alpha-vantage")
    public Map<String, Object> alphaVantage(
            ActivatedJob job, @Variable String requestId, @Variable List<String> symbols) {
        return fetch(ProviderId.ALPHA_VANTAGE, job, requestId, symbols);
    }

    @JobWorker(type = "fetch-twelve-data")
    public Map<String, Object> twelveData(
            ActivatedJob job, @Variable String requestId, @Variable List<String> symbols) {
        return fetch(ProviderId.TWELVE_DATA, job, requestId, symbols);
    }

    private Map<String, Object> fetch(ProviderId id, ActivatedJob job, String requestId, List<String> symbols) {
        try {
            store.saveRaw(requestId, providers.get(id).fetch(symbols));
            return Map.of(outcomeVariable(id), ProviderOutcome.OK.name());
        } catch (BusinessProviderException e) {
            throw new BpmnError(REJECTED_ERROR_CODE, e.getMessage());
        } catch (TransientProviderException e) {
            throw new JobError(e.getMessage(), null, job.getRetries() - 1, attempt -> retryBackoff, e);
        }
    }

    static String outcomeVariable(ProviderId id) {
        return "outcome_" + id.name();
    }
}
