package app.majung.server.pickup.api;

import app.majung.server.pickup.service.KakaoPlaceSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "10. 장소 검색 (Place Search)", description = "카카오 로컬 키워드·도로명/지번 주소 통합 한국 장소 검색 API")
@RestController
public class PlaceSearchApi {
    public record SearchItem(
            @Schema(description = "표시 장소명", example = "의왕역 1호선 1번출구") String label,
            @Schema(description = "도로명 또는 지번 주소", example = "경기 의왕시 삼동 192-57") String address,
            @Schema(description = "장소 분류", example = "지하철역") String category,
            @Schema(description = "위도") double latitude,
            @Schema(description = "경도") double longitude) { }

    public record SearchResponse(
            @Schema(description = "서버 카카오 장소 검색 활성화 여부") boolean enabled,
            @Schema(description = "검색 결과 목록") List<SearchItem> items,
            @Schema(description = "다음 페이지 존재 여부") boolean hasNext) { }

    private final KakaoPlaceSearchService searchService;

    public PlaceSearchApi(KakaoPlaceSearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(summary = "한국 장소·주소 통합 검색", description = "키워드와 도로명·지번 주소를 함께 조회해 정확한 픽업 좌표를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "장소 검색 결과 반환"),
            @ApiResponse(responseCode = "400", description = "검색어 또는 좌표 형식 오류")
    })
    @GetMapping(value = "/api/v1/places/search", produces = "application/json")
    public SearchResponse search(
            @Parameter(description = "검색어 (1~80자)") @RequestParam String query,
            @Parameter(description = "페이지 번호 (1~3)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "기준 위도 (선택)") @RequestParam(required = false) Double latitude,
            @Parameter(description = "기준 경도 (선택)") @RequestParam(required = false) Double longitude) {
        return searchService.search(query, page, latitude, longitude);
    }
}
