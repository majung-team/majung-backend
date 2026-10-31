package app.majung.server.pickup;

import app.majung.server.MajungServerApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

class PrivateMetricsServerTest {
    @Test void metricsUseSeparateLoopbackPortAndNeverTheApplicationPort() throws Exception {
        try (var context = new SpringApplicationBuilder(MajungServerApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--management.server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:private-metrics;MODE=PostgreSQL",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=create-drop", "--spring.session.jdbc.initialize-schema=always",
                "--majung.login.enabled=false", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")) {
            int app = ((WebServerApplicationContext) context).getWebServer().getPort();
            int management = context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
            assertNotEquals(app, management);
            var client = HttpClient.newHttpClient();
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app + "/api/v1/health"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app + "/actuator/prometheus"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode());
            response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + management + "/actuator/prometheus"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("http_server_requests_seconds_bucket"));
            assertTrue(response.body().contains("hikaricp_connections_active"));
            assertFalse(response.body().contains("JSESSIONID="));
        }
    }
}
