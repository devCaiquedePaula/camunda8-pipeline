package dev.assetpipeline.support;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Camunda 8 orchestration cluster (Zeebe + Operate) for integration tests, same image and config as docker-compose.yml. */
public class CamundaContainer extends GenericContainer<CamundaContainer> {

    public static final String IMAGE = "camunda/camunda:8.10.9";
    private static final int GRPC_PORT = 26500;
    private static final int REST_PORT = 8080;
    private static final int MANAGEMENT_PORT = 9600;

    public CamundaContainer() {
        super(IMAGE);
        withExposedPorts(GRPC_PORT, REST_PORT, MANAGEMENT_PORT);
        withCopyFileToContainer(
                MountableFile.forHostPath("docker/camunda/application-h2.yaml"),
                "/usr/local/camunda/config/application.yaml");
        waitingFor(Wait.forHttp("/actuator/health/status")
                .forPort(MANAGEMENT_PORT)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3)));
    }

    public String grpcAddress() {
        return "http://" + getHost() + ":" + getMappedPort(GRPC_PORT);
    }

    public String restAddress() {
        return "http://" + getHost() + ":" + getMappedPort(REST_PORT);
    }
}
