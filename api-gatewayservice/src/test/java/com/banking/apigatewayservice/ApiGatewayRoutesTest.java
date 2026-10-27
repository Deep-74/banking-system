package com.banking.apigatewayservice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the gateway route definitions in application.yaml.
 * Binds the YAML directly, so no Spring context, Redis or backend is needed.
 */
class ApiGatewayRoutesTest {

    private static List<RouteDefinition> routes;

    @BeforeAll
    static void loadRoutes() throws Exception {
        var sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(s -> env.getPropertySources().addLast(s));

        routes = Binder.get(env)
                .bind("spring.cloud.gateway.routes", Bindable.listOf(RouteDefinition.class))
                .orElseThrow(() -> new IllegalStateException("No gateway routes bound"));
    }

    private Map<String, RouteDefinition> routesById() {
        return routes.stream().collect(Collectors.toMap(RouteDefinition::getId, Function.identity()));
    }

    @Test
    void fourRoutesAreDefined() {
        assertThat(routes).hasSize(4);
    }

    @Test
    void allFourServiceRoutesAreRegistered() {
        assertThat(routesById().keySet()).containsExactlyInAnyOrder(
                "account-service",
                "transaction-service",
                "payment-service",
                "fraud-detection-service");
    }

    @Test
    void accountRouteTargetsAccountServicePort() {
        assertThat(routesById().get("account-service").getUri().toString())
                .isEqualTo("http://localhost:8081");
    }

    @Test
    void transactionRouteTargetsTransactionServicePort() {
        assertThat(routesById().get("transaction-service").getUri().toString())
                .isEqualTo("http://localhost:8082");
    }

    @Test
    void paymentRouteTargetsPaymentServicePort() {
        assertThat(routesById().get("payment-service").getUri().toString())
                .isEqualTo("http://localhost:8083");
    }

    @Test
    void fraudRouteTargetsFraudDetectionServicePort() {
        assertThat(routesById().get("fraud-detection-service").getUri().toString())
                .isEqualTo("http://localhost:8084");
    }

    @Test
    void eachRouteHasRequestRateLimiterFilter() {
        routesById().values().forEach(route ->
                assertThat(route.getFilters())
                        .as("route %s", route.getId())
                        .anySatisfy(f -> assertThat(f.getName()).isEqualTo("RequestRateLimiter")));
    }
}

