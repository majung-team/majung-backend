package app.majung.server.pickup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Administrator bootstrap key stays on the server; it cannot create pickups. */
@Component
class DeploymentPolicy {
    private final String creationKey;
    private final boolean developmentToolsEnabled;

    DeploymentPolicy(@Value("${majung.deployment:false}") boolean deployment,
                     @Value("${majung.admin-key:${majung.creation-key:}}") String creationKey,
                     @Value("${majung.development-tools:true}") boolean developmentTools,
                     @Value("${majung.retention.enabled:false}") boolean retentionEnabled,
                     @Value("${majung.retention.period:PT24H}") Duration retention,
                     @Value("${majung.rate-limit.enabled:true}") boolean rateLimitEnabled) {
        if (retention.compareTo(Duration.ofHours(1)) < 0) {
            throw new IllegalArgumentException("Retention must be at least one hour");
        }
        if (deployment && (creationKey.length() < 32 || developmentTools || !retentionEnabled || !rateLimitEnabled)) {
            throw new IllegalArgumentException("Deployment requires an admin key, disabled development tools, retention and rate limiting");
        }
        this.creationKey = creationKey;
        this.developmentToolsEnabled = developmentTools && creationKey.isEmpty();
    }

    boolean developmentToolsEnabled() { return developmentToolsEnabled; }

    void requireAdmin(String authorization) {
        String supplied = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        if (creationKey.isEmpty()) throw new ApiException(403, "ADMIN_DISABLED", "관리자 기능이 설정되지 않았습니다.");
        if (supplied == null || supplied.length() != creationKey.length()
                || !MessageDigest.isEqual(creationKey.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(403, "ADMIN_REQUIRED", "관리자 권한이 필요합니다.");
        }
    }
}
