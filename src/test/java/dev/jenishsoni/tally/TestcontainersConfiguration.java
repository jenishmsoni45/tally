package dev.jenishsoni.tally;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Throwaway admin API key, only for the app started against these test containers. */
    public static final String ADMIN_API_KEY = "throwaway-test-admin-key-not-a-secret";

    // Throwaway password for the tally_app role in a container that is deleted after the run.
    private static final String APP_PASSWORD = "tally_app_test";

    @Bean
    @ServiceConnection
    LgtmStackContainer grafanaLgtmContainer() {
        return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:0.37.0"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
    }

    @Bean
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
    }

    /**
     * Not a service connection, which would make the app connect as the container's owner
     * user. Flyway runs as the owner and creates tally_app; the app connects as tally_app.
     */
    @Bean
    DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", () -> "tally_app");
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
            registry.add("spring.flyway.placeholders.appPassword", () -> APP_PASSWORD);
        };
    }

    // Set here rather than per test class, so every test shares one cached context and one set of containers.
    @Bean
    DynamicPropertyRegistrar adminProperties() {
        return registry -> registry.add("tally.admin.api-key", () -> ADMIN_API_KEY);
    }

}
