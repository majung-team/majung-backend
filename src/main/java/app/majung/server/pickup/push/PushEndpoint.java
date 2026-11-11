package app.majung.server.pickup.push;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * The server calls the subscription URL itself, so only the browser vendors' push services are accepted.
 * This keeps a passenger from pointing the server at an internal address.
 */
public final class PushEndpoint {
    public static final int MAX_LENGTH = 1024;
    // fcm.googleapis.com: Chrome/Edge/Android, push.services.mozilla.com: Firefox,
    // push.apple.com: Safari and home-screen web apps, notify.windows.com: legacy Edge.
    private static final List<String> SERVICES = List.of(
            "fcm.googleapis.com", "push.services.mozilla.com", "push.apple.com", "notify.windows.com");

    private PushEndpoint() { }

    public static String validate(String endpoint) {
        return validate(endpoint, false);
    }

    public static String validate(String endpoint, boolean apnsEnabled) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_LENGTH) throw invalid();
        String stripped = endpoint.strip();
        if (apnsEnabled && stripped.regionMatches(true, 0, "apns://", 0, 7)) {
            String token = stripped.substring(7);
            if (!token.matches("[0-9a-fA-F]{64,256}")) throw invalid();
            return "apns://" + token.toLowerCase(Locale.ROOT);
        }
        URI uri;
        try {
            uri = new URI(stripped);
        } catch (URISyntaxException e) {
            throw invalid();
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowedHost = SERVICES.stream().anyMatch(service -> host.equals(service) || host.endsWith("." + service));
        if (!"https".equals(uri.getScheme()) || !allowedHost || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawFragment() != null
                || uri.getRawPath() == null || uri.getRawPath().length() < 2) {
            throw invalid();
        }
        return uri.toString();
    }

    private static ApiException invalid() {
        return new ApiException(400, "INVALID_PUSH_ENDPOINT", "이 브라우저의 알림 주소를 사용할 수 없습니다.");
    }
}
