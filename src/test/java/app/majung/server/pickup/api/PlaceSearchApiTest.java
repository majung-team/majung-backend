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

    @Test
    void keepsNationwideStationMatchesAheadOfDistantPartialTokenMatches() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/local/search/keyword.json", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String json;
            if (query != null && query.contains("radius=20000")) {
                json = """
                    {
                      "meta": { "is_end": false },
                      "documents": [
                        {
                          "place_name": "서울서남부농협 신림역지점",
                          "road_address_name": "서울 관악구 남부순환로 1597",
                          "category_group_name": "은행",
                          "x": "126.9278",
                          "y": "37.4842"
                        }
                      ]
                    }
                    """;
            } else {
                json = """
                    {
                      "meta": { "is_end": false },
                      "documents": [
                        {
                          "place_name": "서울역",
                          "road_address_name": "서울 중구 한강대로 405",
                          "category_group_name": "지하철역",
                          "x": "126.9707",
                          "y": "37.5540"
                        },
                        {
                          "place_name": "서울역 1호선",
                          "road_address_name": "서울 중구 세종대로 지하 2",
                          "category_group_name": "지하철역",
                          "x": "126.9720",
                          "y": "37.5559"
                        }
                      ]
                    }
                    """;
            }
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.createContext("/v2/local/search/address.json", exchange -> {
            String json = """
                {
                  "meta": { "is_end": true },
                  "documents": []
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
            PlaceSearchApi.SearchResponse response = service.search("서울역", 1, 37.3208, 126.9482);
            assertTrue(response.enabled());
            assertEquals(3, response.items().size());
            assertEquals("서울역", response.items().get(0).label());
            assertEquals("서울역 1호선", response.items().get(1).label());
            assertEquals("서울서남부농협 신림역지점", response.items().get(2).label());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsNationwideAirportAheadOfNearbyCommercialPrefixMatches() throws Exception {
        String nearbyJson = """
            {
              "meta": { "is_end": false },
              "documents": [
                {
                  "place_name": "인천공항밴",
                  "road_address_name": "경기 안산시 단원구 선부광장남로 17",
                  "category_name": "교통,수송 > 콜밴",
                  "x": "126.8090",
                  "y": "37.3340"
                },
                {
                  "place_name": "인천공항택시 에이스팀",
                  "road_address_name": "서울 금천구 시흥대로48길 12",
                  "category_name": "교통,수송 > 콜택시",
                  "x": "126.9001",
                  "y": "37.4560"
                }
              ]
            }
            """;
        String nationwideJson = """
            {
              "meta": { "is_end": false },
              "documents": [
                {
                  "place_name": "인천국제공항",
                  "road_address_name": "인천 영종구 공항로 272",
                  "category_name": "교통,수송 > 공항",
                  "x": "126.4505",
                  "y": "37.4492"
                },
                {
                  "place_name": "인천국제공항 제1여객터미널",
                  "road_address_name": "인천 중구 공항로 271",
                  "category_name": "교통,수송 > 공항",
                  "x": "126.4514",
                  "y": "37.4487"
                }
              ]
            }
            """;
        HttpServer server = startKeywordMockServer(nearbyJson, nationwideJson);
        try {
            KakaoPlaceSearchService service = new KakaoPlaceSearchService(
                    "test-rest-key",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            PlaceSearchApi.SearchResponse response = service.search("인천공항", 1, 37.3208, 126.9482);
            assertTrue(response.enabled());
            assertEquals(4, response.items().size());
            assertEquals("인천국제공항", response.items().get(0).label());
            assertEquals("인천국제공항 제1여객터미널", response.items().get(1).label());
            assertEquals("인천공항밴", response.items().get(2).label());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsNearbyChainStoresAheadOfNationwideChainStores() throws Exception {
        String nearbyJson = """
            {
              "meta": { "is_end": false },
              "documents": [
                {
                  "place_name": "스타벅스 의왕삼동DT점",
                  "road_address_name": "경기 의왕시 부곡중앙로 1",
                  "category_group_name": "카페",
                  "x": "126.9490",
                  "y": "37.3215"
                }
              ]
            }
            """;
        String nationwideJson = """
            {
              "meta": { "is_end": false },
              "documents": [
                {
                  "place_name": "스타벅스 더북한산점",
                  "road_address_name": "서울 은평구 대서문길 24",
                  "category_group_name": "카페",
                  "x": "126.9465",
                  "y": "37.6580"
                }
              ]
            }
            """;
        HttpServer server = startKeywordMockServer(nearbyJson, nationwideJson);
        try {
            KakaoPlaceSearchService service = new KakaoPlaceSearchService(
                    "test-rest-key",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            PlaceSearchApi.SearchResponse response = service.search("스타벅스", 1, 37.3208, 126.9482);
            assertTrue(response.enabled());
            assertEquals(2, response.items().size());
            assertEquals("스타벅스 의왕삼동DT점", response.items().get(0).label());
            assertEquals("스타벅스 더북한산점", response.items().get(1).label());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startKeywordMockServer(String nearbyJson, String nationwideJson) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/local/search/keyword.json", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String json = (query != null && query.contains("radius=20000")) ? nearbyJson : nationwideJson;
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.createContext("/v2/local/search/address.json", exchange -> {
            byte[] out = """
                { "meta": { "is_end": true }, "documents": [] }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return server;
    }
}
