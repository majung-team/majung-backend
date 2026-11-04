package app.majung.server.pickup.api;

import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "09. 클라이언트 설정 (Client Config)", description = "카카오맵 자바스크립트 키, VAPID 웹 푸시 공개키 등 클라이언트 런타임 환경 설정 조회 API")
@RestController
public class ClientConfigurationApi {
    public record Configuration(
            @Schema(description = "개발자 도구(디버깅 패널) 활성화 여부", example = "false") boolean developmentToolsEnabled,
            @Schema(description = "카카오맵 자바스크립트 공개 앱 키 (웹/모바일 연동용)", example = "3f82a1b94c...") String kakaoMapJavaScriptKey,
            @Schema(description = "VAPID Web Push 공개 키 (푸시 구독용)", example = "BNc8xP...") String webPushPublicKey) { }

    private final DeploymentPolicy deploymentPolicy;
    private final String kakaoMapJavaScriptKey;
    private final ArrivalPushService arrivalPush;

    // The Kakao Maps JavaScript key is public by design and restricted to registered web domains.
    public ClientConfigurationApi(DeploymentPolicy deploymentPolicy,
                           ArrivalPushService arrivalPush,
                           @Value("${majung.kakao-map.javascript-key:}") String kakaoMapJavaScriptKey) {
        this.arrivalPush = arrivalPush;
        this.deploymentPolicy = deploymentPolicy;
        this.kakaoMapJavaScriptKey = kakaoMapJavaScriptKey.isBlank() ? null : kakaoMapJavaScriptKey.strip();
    }

    @Operation(summary = "클라이언트 환경 설정 조회", description = "프론트엔드 및 모바일 앱 실행에 필요한 카카오맵 키, 웹 푸시 VAPID 공개키 및 설정값을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정값 조회 성공")
    })
    @GetMapping(value = "/api/v1/config", produces = "application/json")
    Configuration configuration() {
        return new Configuration(deploymentPolicy.developmentToolsEnabled(), kakaoMapJavaScriptKey,
                arrivalPush.enabled() ? arrivalPush.publicKey() : null);
    }
}
