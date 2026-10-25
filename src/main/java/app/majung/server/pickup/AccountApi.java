package app.majung.server.pickup;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
class AccountApi {
    record Profile(UUID memberId, String majungId, String nickname, boolean profileReady) { }
    record SearchResult(UUID memberId, String majungId, String nickname) { }
    record Connection(UUID connectionId, String majungId, String nickname, String status, String direction) { }
    record Nickname(@NotBlank @Size(max = 80) String nickname) { }
    record Target(UUID memberId) { }
    record Session(boolean loginEnabled, boolean authenticated, Profile member, String csrfToken) { }
    private final MemberService memberService;
    private final boolean enabled;
    AccountApi(MemberService memberService, @Value("${majung.login.enabled:false}") boolean enabled) {
        this.memberService = memberService;
        this.enabled = enabled;
    }
    @GetMapping("/api/v1/auth/session")
    Session session(Authentication authentication, CsrfToken csrf) {
        UUID id = memberIdOrNull(authentication);
        return new Session(enabled, id != null, id == null ? null : MemberService.profile(memberService.current(id)), csrf.getToken());
    }
    @GetMapping("/api/v1/auth/login")
    void login(HttpServletResponse response) throws IOException {
        if (!enabled) throw new ApiException(503, "LOGIN_NOT_CONFIGURED", "로그인 준비 중이에요.");
        response.setStatus(302);
        response.setHeader("Location", "/api/v1/auth/authorize/kakao");
    }
    @GetMapping("/api/v1/accounts/me")
    Profile me(Authentication authentication) { return MemberService.profile(memberService.current(memberId(authentication))); }
    @PatchMapping("/api/v1/accounts/me")
    Profile nickname(Authentication authentication, @Valid @RequestBody Nickname request) {
        return memberService.nickname(memberId(authentication), request.nickname());
    }
    @GetMapping("/api/v1/accounts/search")
    List<SearchResult> search(Authentication authentication, @RequestParam String query,
                              @RequestParam(defaultValue = "0") int page) {
        return memberService.search(memberId(authentication), query, page);
    }
    @GetMapping("/api/v1/connections")
    List<Connection> connections(Authentication authentication, @RequestParam(defaultValue = "0") int page) {
        return memberService.connections(memberId(authentication), page);
    }
    @PostMapping("/api/v1/connections")
    Connection request(Authentication authentication, @RequestBody Target target) {
        if (target.memberId() == null) throw new ApiException(400, "TARGET_REQUIRED", "연결할 사람을 선택해주세요.");
        return memberService.request(memberId(authentication), target.memberId());
    }
    @PostMapping("/api/v1/connections/{id}/{action}")
    Connection change(Authentication authentication, @PathVariable UUID id, @PathVariable String action) {
        return memberService.change(memberId(authentication), id, action);
    }
    private UUID memberId(Authentication authentication) {
        UUID id = memberIdOrNull(authentication);
        if (id == null) throw new ApiException(401, "LOGIN_REQUIRED", "로그인이 필요해요.");
        return id;
    }
    private UUID memberIdOrNull(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof OAuth2User principal)) return null;
        try { return UUID.fromString(principal.getName()); }
        catch (IllegalArgumentException exception) { return null; }
    }
}
