package app.majung.server.pickup.service;

import app.majung.server.pickup.api.PlaceSearchApi;
import app.majung.server.pickup.common.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Server-side proxy for Korean Kakao Local keyword and road/jibun address search. */
@Service
public class KakaoPlaceSearchService {
    private static final Logger log = LoggerFactory.getLogger(KakaoPlaceSearchService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String restApiKey;
    private final String baseUrl;
    private final HttpClient client;

    public KakaoPlaceSearchService(
            @Value("${majung.kakao-local.rest-api-key:${KAKAO_CLIENT_ID:}}") String restApiKey,
            @Value("${majung.kakao-local.base-url:https://dapi.kakao.com}") String baseUrl) {
        this.restApiKey = restApiKey == null || restApiKey.isBlank() ? null : restApiKey.strip();
        String trimmedBase = baseUrl == null || baseUrl.isBlank() ? "https://dapi.kakao.com" : baseUrl.strip();
        while (trimmedBase.endsWith("/")) {
            trimmedBase = trimmedBase.substring(0, trimmedBase.length() - 1);
        }
        this.baseUrl = trimmedBase;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public boolean enabled() {
        return restApiKey != null;
    }

    public PlaceSearchApi.SearchResponse search(String rawQuery, int page, Double latitude, Double longitude) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.isEmpty() || query.length() > 80 || page < 1 || page > 3) {
            throw new ApiException(400, "PLACE_SEARCH_INVALID", "검색어는 1~80자, 페이지는 1~3 사이로 입력해주세요.");
        }
        if ((latitude != null) != (longitude != null)) {
            throw new ApiException(400, "PLACE_SEARCH_INVALID", "위치 기준 좌표가 올바르지 않아요.");
        }
        if (latitude != null && (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180)) {
            throw new ApiException(400, "PLACE_SEARCH_INVALID", "위치 기준 좌표가 범위를 벗어났어요.");
        }
        if (!enabled()) {
            return new PlaceSearchApi.SearchResponse(false, List.of(), false);
        }

        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String keywordUrl = baseUrl + "/v2/local/search/keyword.json?query=" + encoded + "&page=" + page + "&size=10"
                + (latitude != null ? "&x=" + longitude + "&y=" + latitude + "&radius=20000" : "");
        String addressUrl = baseUrl + "/v2/local/search/address.json?query=" + encoded + "&page=" + page + "&size=5";

        CompletableFuture<UpstreamPage> keywordFuture = CompletableFuture.supplyAsync(() -> fetch(keywordUrl, false));
        CompletableFuture<UpstreamPage> addressFuture = CompletableFuture.supplyAsync(() -> fetch(addressUrl, true));

        UpstreamPage keywordResult = safeJoin(keywordFuture);
        UpstreamPage addressResult = safeJoin(addressFuture);
        if (keywordResult == null && addressResult == null) {
            throw new ApiException(503, "PLACE_SEARCH_FAILED", "장소 검색을 완료하지 못했어요. 잠시 후 다시 시도해주세요.");
        }

        Map<String, PlaceSearchApi.SearchItem> deduped = new LinkedHashMap<>();
        boolean hasNext = false;
        if (keywordResult != null) {
            hasNext |= keywordResult.hasNext();
            for (PlaceSearchApi.SearchItem item : keywordResult.items()) {
                deduped.putIfAbsent(dedupeKey(item), item);
            }
        }
        if (addressResult != null) {
            hasNext |= addressResult.hasNext();
            for (PlaceSearchApi.SearchItem item : addressResult.items()) {
                deduped.putIfAbsent(dedupeKey(item), item);
            }
        }
        List<PlaceSearchApi.SearchItem> merged = new ArrayList<>(deduped.values());
        if (merged.size() > 15) {
            merged = List.copyOf(merged.subList(0, 15));
        }
        return new PlaceSearchApi.SearchResponse(true, merged, page < 3 && hasNext);
    }

    private record UpstreamPage(List<PlaceSearchApi.SearchItem> items, boolean hasNext) { }

    private static UpstreamPage safeJoin(CompletableFuture<UpstreamPage> future) {
        try {
            return future.join();
        } catch (Exception e) {
            return null;
        }
    }

    private UpstreamPage fetch(String url, boolean isAddress) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .header("Authorization", "KakaoAK " + restApiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Kakao local search rejected: status={}", response.statusCode());
                return null;
            }
            JsonNode root = JSON.readTree(response.body());
            boolean isEnd = root.path("meta").path("is_end").asBoolean(true);
            List<PlaceSearchApi.SearchItem> items = new ArrayList<>();
            for (JsonNode doc : root.path("documents")) {
                PlaceSearchApi.SearchItem parsed = parseItem(doc, isAddress);
                if (parsed != null) items.add(parsed);
            }
            return new UpstreamPage(items, !isEnd);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Kakao local search failed: {}", e.getClass().getSimpleName());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static PlaceSearchApi.SearchItem parseItem(JsonNode doc, boolean isAddress) {
        if (doc == null || !doc.isObject()) return null;
        String xRaw = doc.path("x").asText("").strip();
        String yRaw = doc.path("y").asText("").strip();
        if (xRaw.isEmpty() || yRaw.isEmpty()) return null;
        double lng;
        double lat;
        try {
            lng = Double.parseDouble(xRaw);
            lat = Double.parseDouble(yRaw);
        } catch (NumberFormatException e) {
            return null;
        }
        if (!Double.isFinite(lat) || !Double.isFinite(lng)
                || Math.abs(lat) > 90 || Math.abs(lng) > 180
                || (lat == 0.0 && lng == 0.0)) {
            return null;
        }

        String roadAddress = doc.path("road_address_name").asText("").strip();
        if (roadAddress.isEmpty()) {
            roadAddress = doc.path("road_address").path("address_name").asText("").strip();
        }
        String jibunAddress = doc.path("address_name").asText("").strip();
        if (jibunAddress.isEmpty()) {
            jibunAddress = doc.path("address").path("address_name").asText("").strip();
        }
        String buildingName = doc.path("road_address").path("building_name").asText("").strip();
        String placeName = doc.path("place_name").asText("").strip();

        String rawLabel = !placeName.isEmpty()
                ? placeName
                : (!buildingName.isEmpty() ? buildingName : (!roadAddress.isEmpty() ? roadAddress : jibunAddress));
        if (rawLabel.isEmpty()) return null;
        String label = rawLabel.length() > 120 ? rawLabel.substring(0, 120) : rawLabel;

        String displayAddress = !roadAddress.isEmpty() ? roadAddress : jibunAddress;
        if (displayAddress.length() > 200) {
            displayAddress = displayAddress.substring(0, 200);
        }

        String category = doc.path("category_group_name").asText("").strip();
        if (category.isEmpty()) {
            String fullCategory = doc.path("category_name").asText("").strip();
            if (!fullCategory.isEmpty()) {
                int idx = fullCategory.lastIndexOf('>');
                category = (idx >= 0 ? fullCategory.substring(idx + 1) : fullCategory).strip();
            } else if (isAddress) {
                category = "주소";
            }
        }
        return new PlaceSearchApi.SearchItem(
                label,
                displayAddress.isEmpty() ? null : displayAddress,
                category.isEmpty() ? null : category,
                lat,
                lng
        );
    }

    private static String dedupeKey(PlaceSearchApi.SearchItem item) {
        return item.label() + ":" + Math.round(item.latitude() * 100_000) + ":" + Math.round(item.longitude() * 100_000);
    }
}
