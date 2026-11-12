package app.majung.server.pickup.dev;

import app.majung.server.pickup.common.ApiException;
import app.majung.server.pickup.common.DeploymentPolicy;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Signed session + CSRF + brute-force protection for {@link OpsConsoleApi}.
 *
 * <p>When deployed ({@code majung.deployment=true}) or configured with {@code majung.admin-key},
 * requires valid admin key login or {@code Authorization: Bearer <MAJUNG_ADMIN_KEY>}.
 * Stateless HMAC-SHA256 tokens stay valid across blue-green slots and restarts.
 */
@Service
public class OpsSessionService {
    public static final String COOKIE_NAME = "MAJUNG_OPS_SESSION";
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final long LOCKOUT_WINDOW_MILLIS = 60_000L;
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final DeploymentPolicy deploymentPolicy;
    private final Clock clock;
    private final boolean deployment;
    private final boolean authRequired;
    private final byte[] signingKey;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, FailedWindow> failedAttempts = new HashMap<>();

    private static final class FailedWindow {
        long windowStart;
        int count;
    }

    public record SessionStatus(boolean authenticated, boolean authRequired, String csrfToken) { }

    public OpsSessionService(
            DeploymentPolicy deploymentPolicy,
            Clock clock,
            @Value("${majung.deployment:false}") boolean deployment,
            @Value("${majung.admin-key:${majung.creation-key:}}") String adminKey) {
        this.deploymentPolicy = deploymentPolicy;
        this.clock = clock;
        this.deployment = deployment;
        String key = adminKey == null ? "" : adminKey;
        this.authRequired = deployment || !key.isEmpty();
        if (!key.isEmpty()) {
            this.signingKey = sha256(("majung-ops-session-key:" + key).getBytes(StandardCharsets.UTF_8));
        } else {
            byte[] ephemeral = new byte[32];
            random.nextBytes(ephemeral);
            this.signingKey = ephemeral;
        }
    }

    public boolean isAuthRequired() {
        return authRequired;
    }

    public boolean isDeployment() {
        return deployment;
    }

    public SessionStatus sessionStatus(HttpServletRequest request, HttpServletResponse response) {
        if (!authRequired) {
            requireLoopbackIfDev(request);
            String existingSid = validSessionIdFromCookie(request);
            if (existingSid == null) {
                existingSid = issueCookie(request, response);
            }
            return new SessionStatus(true, false, csrfFor(existingSid));
        }
        String sid = validSessionIdFromCookie(request);
        if (sid != null) {
            return new SessionStatus(true, true, csrfFor(sid));
        }
        return new SessionStatus(false, true, null);
    }

    public SessionStatus login(String suppliedAdminKey, HttpServletRequest request, HttpServletResponse response) {
        if (!authRequired) {
            return sessionStatus(request, response);
        }
        String peer = peerKey(request);
        checkRateLimit(peer);
        try {
            deploymentPolicy.requireAdmin("Bearer " + (suppliedAdminKey == null ? "" : suppliedAdminKey));
        } catch (ApiException failure) {
            recordFailure(peer);
            throw new ApiException(403, "INVALID_ADMIN_KEY", "관리자 키가 올바르지 않습니다.");
        }
        clearFailures(peer);
        String sid = issueCookie(request, response);
        return new SessionStatus(true, true, csrfFor(sid));
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        StringBuilder header = new StringBuilder();
        header.append(COOKIE_NAME).append("=; Path=/api/v1/ops; HttpOnly; SameSite=Strict; Max-Age=0");
        if (shouldUseSecureCookie(request)) {
            header.append("; Secure");
        }
        response.addHeader("Set-Cookie", header.toString());
    }

    public void requireAuthorized(HttpServletRequest request, boolean mutating) {
        if (!authRequired) {
            requireLoopbackIfDev(request);
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ") && authRequired) {
            deploymentPolicy.requireAdmin(auth);
            return;
        }
        String sid = validSessionIdFromCookie(request);
        if (sid == null) {
            throw new ApiException(401, "OPS_LOGIN_REQUIRED", "관리자 인증이 필요합니다.");
        }
        if (mutating) {
            verifySameOrigin(request);
            String suppliedCsrf = request.getHeader("X-Ops-CSRF");
            String expectedCsrf = csrfFor(sid);
            if (suppliedCsrf == null
                    || suppliedCsrf.length() != expectedCsrf.length()
                    || !MessageDigest.isEqual(expectedCsrf.getBytes(StandardCharsets.UTF_8),
                    suppliedCsrf.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(403, "OPS_CSRF_REQUIRED", "보안 토큰이 만료되었거나 올바르지 않습니다. 새로고침 후 다시 시도해주세요.");
            }
        }
    }

    private String issueCookie(HttpServletRequest request, HttpServletResponse response) {
        long exp = clock.instant().plus(SESSION_TTL).getEpochSecond();
        String sid = UUID.randomUUID().toString();
        String payload = exp + "." + sid;
        String sig = hmacBase64Url(payload);
        String value = payload + "." + sig;

        StringBuilder cookie = new StringBuilder();
        cookie.append(COOKIE_NAME).append("=").append(value)
                .append("; Path=/api/v1/ops; HttpOnly; SameSite=Strict; Max-Age=")
                .append(SESSION_TTL.toSeconds());
        if (shouldUseSecureCookie(request)) {
            cookie.append("; Secure");
        }
        response.addHeader("Set-Cookie", cookie.toString());
        return sid;
    }

    private String validSessionIdFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (!COOKIE_NAME.equals(cookie.getName())) {
                continue;
            }
            String raw = cookie.getValue();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] parts = raw.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            long exp;
            try {
                exp = Long.parseLong(parts[0]);
            } catch (NumberFormatException ignored) {
                return null;
            }
            if (clock.instant().getEpochSecond() > exp) {
                return null;
            }
            String expectedSig = hmacBase64Url(parts[0] + "." + parts[1]);
            if (parts[2].length() != expectedSig.length()
                    || !MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            return parts[1];
        }
        return null;
    }

    private String csrfFor(String sessionId) {
        return hmacBase64Url("csrf:" + sessionId);
    }

    private boolean shouldUseSecureCookie(HttpServletRequest request) {
        return deployment || request.isSecure();
    }

    private void requireLoopbackIfDev(HttpServletRequest request) {
        if (!deploymentPolicy.developmentToolsEnabled()) {
            throw new ApiException(403, "OPS_DISABLED", "관리자 키가 설정되지 않아 사용할 수 없습니다.");
        }
        String addr = request.getRemoteAddr();
        if (addr == null) {
            throw new ApiException(403, "LOOPBACK_ONLY", "로컬 환경에서만 접근 가능합니다.");
        }
        try {
            if (!InetAddress.getByName(addr).isLoopbackAddress()) {
                throw new ApiException(403, "LOOPBACK_ONLY", "로컬 환경에서만 접근 가능합니다.");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(403, "LOOPBACK_ONLY", "로컬 환경에서만 접근 가능합니다.");
        }
    }

    private void verifySameOrigin(HttpServletRequest request) {
        String host = request.getHeader("Host");
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && host != null && !host.isBlank()) {
            try {
                URI parsed = URI.create(origin);
                if (!deployment && isLoopbackHost(parsed.getHost()) && isLoopbackRequest(request)) {
                    return;
                }
                String originHost = parsed.getPort() > 0 ? parsed.getHost() + ":" + parsed.getPort() : parsed.getHost();
                if (originHost == null || !originHost.equalsIgnoreCase(host)) {
                    throw new ApiException(403, "OPS_ORIGIN_MISMATCH", "허용되지 않은 출처 요청입니다.");
                }
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiException(403, "OPS_ORIGIN_MISMATCH", "허용되지 않은 출처 요청입니다.");
            }
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) return false;
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLoopbackRequest(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return addr != null && isLoopbackHost(addr);
    }

    private synchronized void checkRateLimit(String peer) {
        long now = clock.millis();
        FailedWindow win = failedAttempts.get(peer);
        if (win != null && now - win.windowStart < LOCKOUT_WINDOW_MILLIS && win.count >= MAX_FAILED_ATTEMPTS) {
            throw new ApiException(429, "TOO_MANY_ATTEMPTS", "관리자 인증 시도가 너무 많습니다. 1분 후 다시 시도해주세요.");
        }
    }

    private synchronized void recordFailure(String peer) {
        long now = clock.millis();
        failedAttempts.entrySet().removeIf(e -> now - e.getValue().windowStart >= LOCKOUT_WINDOW_MILLIS);
        FailedWindow win = failedAttempts.computeIfAbsent(peer, k -> {
            FailedWindow w = new FailedWindow();
            w.windowStart = now;
            return w;
        });
        if (now - win.windowStart >= LOCKOUT_WINDOW_MILLIS) {
            win.windowStart = now;
            win.count = 0;
        }
        win.count++;
    }

    private synchronized void clearFailures(String peer) {
        failedAttempts.remove(peer);
    }

    private static String peerKey(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return addr == null ? "unknown" : addr;
    }

    private String hmacBase64Url(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
