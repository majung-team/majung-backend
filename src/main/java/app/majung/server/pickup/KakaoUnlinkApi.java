package app.majung.server.pickup;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class KakaoUnlinkApi {
    private final KakaoUnlinkService unlinkService;
    KakaoUnlinkApi(KakaoUnlinkService unlinkService) { this.unlinkService = unlinkService; }

    @PostMapping(value = "/api/v1/auth/kakao/unlink", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    void unlink(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("app_id") String appId, @RequestParam("user_id") String userId,
            @RequestParam("referrer_type") String referrer) {
        unlinkService.receive(authorization, appId, userId, referrer);
    }
}
