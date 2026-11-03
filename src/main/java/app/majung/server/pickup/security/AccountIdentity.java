package app.majung.server.pickup;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;

final class AccountIdentity {
    private AccountIdentity() { }
    static UUID memberOrNull(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OAuth2User principal)) return null;
        try { return UUID.fromString(principal.getName()); }
        catch (IllegalArgumentException exception) { return null; }
    }
    static UUID requireMember(Authentication authentication) {
        UUID id = memberOrNull(authentication);
        if (id == null) throw new ApiException(401, "LOGIN_REQUIRED", "로그인이 필요해요.");
        return id;
    }
}
