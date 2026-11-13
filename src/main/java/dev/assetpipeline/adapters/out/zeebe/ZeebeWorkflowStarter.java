package dev.assetpipeline.adapters.out.zeebe;

import dev.assetpipeline.domain.WorkflowStarter;
import io.camunda.client.CamundaClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Starts the aggregate-assets process. Runtime failures of the client propagate to the caller. */
@Component
public class ZeebeWorkflowStarter implements WorkflowStarter {

    static final String PROCESS_ID = "aggregate-assets";

    private final CamundaClient client;
    private final Duration providerTimeout;

    public ZeebeWorkflowStarter(
            CamundaClient client, @Value("${assetpipeline.provider-timeout}") Duration providerTimeout) {
        this.client = client;
        this.providerTimeout = providerTimeout;
    }

    @Override
    public void start(String requestId, List<String> symbols) {
        client.newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_ID)
                .latestVersion()
                .variables(Map.of(
                        "requestId", requestId,
                        "symbols", symbols,
                        "providerTimeout", providerTimeout.toString()))
                .send()
                .join();
    }
}
