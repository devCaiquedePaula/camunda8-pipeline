package dev.assetpipeline.support;

import org.testcontainers.containers.GenericContainer;

/** Containers started once per JVM and shared by every integration test class that needs them. */
public final class SharedContainers {

    public static final CamundaContainer CAMUNDA = new CamundaContainer();
    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        CAMUNDA.start();
        REDIS.start();
    }

    private SharedContainers() {
    }
}
