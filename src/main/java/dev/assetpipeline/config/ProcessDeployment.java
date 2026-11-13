package dev.assetpipeline.config;

import io.camunda.client.annotation.Deployment;
import org.springframework.context.annotation.Configuration;

/** Deploys the BPMN process to Zeebe when the application starts. */
@Configuration
@Deployment(resources = "classpath:bpmn/aggregate-assets.bpmn")
public class ProcessDeployment {
}
