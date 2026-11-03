package app.majung.server.pickup;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * The server calls the subscription URL itself, so only the browser vendors' push services are accepted.
 * This keeps a passenger from pointing the server at an internal address.
 */
final class PushEndpoint {
    static final int MAX_LENGTH = 1024;
    // fcm.googleapis.com: Chrome/Edge/Android, push.services.mozilla.com: Firefox,
    // push.apple.com: Safari and home-screen web apps, notify.windows.com: legacy Edge.
    private static final List<String> SERVICES = List.of(
            "fcm.googleapis.com", "push.services.mozilla.com", "push.apple.com", "notify.windows.com");

    private PushEndpoint() { }

    static String validate(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_LENGTH) throw invalid();
        URI uri;
        try {
            uri = new URI(endpoint.strip());
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
