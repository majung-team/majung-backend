package app.majung.server.pickup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:swagger-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
        "majung.rate-limit.enabled=false"
})
@Import(PickupApiTest.TimeConfiguration.class)
class SwaggerDocumentationTest {

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Test
    @DisplayName("OpenAPI JSON 명세서(/v3/api-docs)가 정상적으로 생성되어야 한다")
    void openApiDocsEndpointReturnsValidJson() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("마중 (Majung) API 명세서"), "Title should match");
        assertTrue(body.contains("/api/v1/pickups"), "Pickups path should exist");
        assertTrue(body.contains("/api/v1/ride-requests"), "Ride requests path should exist");
        assertTrue(body.contains("bearerAuth"), "Bearer auth scheme should exist");
        assertTrue(body.contains("sessionCookie"), "Session cookie scheme should exist");
    }

    @Test
    @DisplayName("Swagger UI HTML(/swagger-ui/index.html)이 정상적으로 응답하고 CSP에 unsafe-inline이 포함되어야 한다")
    void swaggerUiHtmlEndpointReturnsOkWithCsp() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/swagger-ui/index.html"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String csp = response.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("'unsafe-inline'"), "CSP must permit unsafe-inline for Swagger UI resources");
    }
}
