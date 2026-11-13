package dev.assetpipeline.adapters.in.worker;

import dev.assetpipeline.application.ConsolidateResults;
import dev.assetpipeline.domain.ProviderId;
import dev.assetpipeline.domain.ProviderOutcome;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ActivatedJob;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Runs after the parallel join: merges what the provider branches stored and reports only the status to Zeebe. */
@Component
public class ConsolidateWorker {

    private final ConsolidateResults consolidate;

    public ConsolidateWorker(ConsolidateResults consolidate) {
        this.consolidate = consolidate;
    }

    // fetchAllVariables: with only @Variable parameters the client would fetch just those, and miss outcome_*.
    @JobWorker(type = "consolidate-and-store", fetchAllVariables = true)
    public Map<String, Object> consolidate(
            ActivatedJob job, @Variable String requestId, @Variable List<String> symbols) {
        Map<String, Object> variables = job.getVariablesAsMap();
        Map<ProviderId, ProviderOutcome> outcomes = new EnumMap<>(ProviderId.class);
        for (ProviderId provider : ProviderId.values()) {
            Object outcome = variables.get(ProviderWorkers.outcomeVariable(provider));
            if (outcome != null) {
                outcomes.put(provider, ProviderOutcome.valueOf(outcome.toString()));
            }
        }
        var result = consolidate.execute(requestId, symbols, outcomes);
        return Map.of("status", result.status().name());
    }
}
