package app.majung.server.pickup.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.majung.server.pickup.common.ApiException;
import app.majung.server.pickup.service.KakaoPlaceSearchService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class PlaceSearchApiTest {
    @Test
    void disabledWhenRestKeyMissingAndValidatesInputs() {
        KakaoPlaceSearchService disabled = new KakaoPlaceSearchService("", "");
        assertFalse(disabled.enabled());
        PlaceSearchApi.SearchResponse response = disabled.search("의왕역", 1, null, null);
        assertFalse(response.enabled());
        assertTrue(response.items().isEmpty());

        assertThrows(ApiException.class, () -> disabled.search("   ", 1, null, null));
        assertThrows(ApiException.class, () -> disabled.search("의왕역", 4, null, null));
        assertThrows(ApiException.class, () -> disabled.search("의왕역", 1, 37.3, null));
        assertThrows(ApiException.class, () -> disabled.search("의왕역", 1, 95.0, 127.0));
    }

    @Test
    void mergesKeywordAndAddressResultsAndDeduplicatesCoordinates() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/local/search/keyword.json", exchange -> {
            assertEquals("KakaoAK test-rest-key", exchange.getRequestHeaders().getFirst("Authorization"));
            String json = """
                {
                  "meta": { "is_end": false },
                  "documents": [
                    {
                      "place_name": "의왕역 1호선",
                      "road_address_name": "경기 의왕시 철도박물관로 10",
                      "address_name": "경기 의왕시 삼동 192-57",
                      "category_group_name": "지하철역",
                      "x": "126.9482",
                      "y": "37.3208"
                    },
                    {
                      "place_name": "의왕역 1호선",
                      "road_address_name": "경기 의왕시 철도박물관로 10",
                      "x": "126.9482",
                      "y": "37.3208"
                    }
                  ]
                }
                """;
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.createContext("/v2/local/search/address.json", exchange -> {
            assertEquals("KakaoAK test-rest-key", exchange.getRequestHeaders().getFirst("Authorization"));
            String json = """
                {
                  "meta": { "is_end": true },
                  "documents": [
                    {
                      "address_name": "경기 의왕시 삼동 192-57",
                      "road_address": {
                        "address_name": "경기 의왕시 철도박물관로 10",
                        "building_name": "의왕역사"
                      },
                      "x": "126.9485",
                      "y": "37.3210"
                    }
                  ]
                }
                """;
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            KakaoPlaceSearchService service = new KakaoPlaceSearchService(
                    "test-rest-key",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            PlaceSearchApi.SearchResponse response = service.search("의왕역", 1, 37.32, 126.94);
            assertTrue(response.enabled());
            assertTrue(response.hasNext());
            assertEquals(2, response.items().size());
            assertEquals("의왕역 1호선", response.items().get(0).label());
            assertEquals("경기 의왕시 철도박물관로 10", response.items().get(0).address());
            assertEquals("지하철역", response.items().get(0).category());
            assertEquals("의왕역사", response.items().get(1).label());
            assertEquals("주소", response.items().get(1).category());
        } finally {
            server.stop(0);
        }
    }
}
