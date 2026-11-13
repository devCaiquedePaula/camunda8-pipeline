package dev.assetpipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.assetpipeline.support.CamundaContainer;
import io.camunda.client.CamundaClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class ZeebeConnectionIT {

    @Container
    static final CamundaContainer camunda = new CamundaContainer();

    @DynamicPropertySource
    static void camundaProperties(DynamicPropertyRegistry registry) {
        registry.add("camunda.client.grpc-address", camunda::grpcAddress);
        registry.add("camunda.client.rest-address", camunda::restAddress);
    }

    @Autowired
    CamundaClient client;

    @Test
    void zeebeTopologyIsReachable() {
        var topology = client.newTopologyRequest().send().join();

        assertThat(topology.getBrokers()).isNotEmpty();
    }
}
