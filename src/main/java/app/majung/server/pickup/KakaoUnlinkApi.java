package app.majung.server.pickup;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "07. 회원 계정 (Accounts)")
@RestController
class KakaoUnlinkApi {
    private final KakaoUnlinkService unlinkService;
    KakaoUnlinkApi(KakaoUnlinkService unlinkService) { this.unlinkService = unlinkService; }

    @Operation(summary = "카카오 연결 끊기 웹훅 콜백", description = "사용자가 카카오 계정 설정에서 앱 연결을 해제했을 때 카카오 인증 서버에서 호출되는 서버 간 웹훅 콜백입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "연결 끊기 처리 성공"),
            @ApiResponse(responseCode = "401", description = "카카오 어드민 키 서명 불일치")
    })
    @PostMapping(value = "/api/v1/auth/kakao/unlink", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    void unlink(@Parameter(description = "카카오 Admin 키 인증 헤더") @RequestHeader(value = "Authorization", required = false) String authorization,
                @Parameter(description = "카카오 앱 ID") @RequestParam("app_id") String appId,
                @Parameter(description = "카카오 회원 번호") @RequestParam("user_id") String userId,
                @Parameter(description = "연결 해제 경로") @RequestParam("referrer_type") String referrer) {
        unlinkService.receive(authorization, appId, userId, referrer);
    }
}
