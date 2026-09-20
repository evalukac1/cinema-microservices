package com.cinema.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "spring.config.import=",
                "eureka.client.enabled=false",
                "cinema.security.admin-username=test-admin",
                "cinema.security.admin-password=test-password"
        }
)
class GatewaySecurityIntegrationTest {

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void shouldAllowPublicHealthCheck() {
        client.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldRejectAnonymousReservationAccess() {
        client.get()
                .uri("/api/reservations/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldRejectAnonymousMovieCreation() {
        client.post()
                .uri("/api/movies")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldRejectIncorrectPassword() {
        client.get()
                .uri("/actuator/info")
                .headers(headers ->
                        headers.setBasicAuth("test-admin", "wrong-password"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldAllowAuthenticatedAdmin() {
        client.get()
                .uri("/actuator/info")
                .headers(headers ->
                        headers.setBasicAuth("test-admin", "test-password"))
                .exchange()
                .expectStatus().isOk();
    }
}