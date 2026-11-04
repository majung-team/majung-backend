package app.majung.server.pickup.security;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.service.*;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;

public final class AccountIdentity {
    private AccountIdentity() { }
    public static UUID memberOrNull(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OAuth2User principal)) return null;
        try { return UUID.fromString(principal.getName()); }
        catch (IllegalArgumentException exception) { return null; }
    }
    public static UUID requireMember(Authentication authentication) {
        UUID id = memberOrNull(authentication);
        if (id == null) throw new ApiException(401, "LOGIN_REQUIRED", "로그인이 필요해요.");
        return id;
    }
}
