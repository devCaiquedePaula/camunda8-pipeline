package dev.assetpipeline.domain;

import java.util.List;

public interface WorkflowStarter {

    void start(String requestId, List<String> symbols);
}
