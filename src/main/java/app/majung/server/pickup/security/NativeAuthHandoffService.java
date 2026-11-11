package app.majung.server.pickup.security;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;

/** Bridges OAuth2 web login to the native iOS app using a short-lived PKCE handoff code. */
@Service
public class NativeAuthHandoffService {
    public static final String NATIVE_CALLBACK_PREFIX = "majung://auth/callback";
    private static final String SESSION_CHALLENGE_ATTR = "majung.native.ios.challenge";
    private static final String SESSION_CHALLENGE_AT_ATTR = "majung.native.ios.challengeAt";
    private static final String HANDOFF_MEMBER_ID = "majung.native.ios.handoffMemberId";
    private static final String HANDOFF_CHALLENGE = "majung.native.ios.handoffChallenge";
    private static final String HANDOFF_EXPIRES_AT = "majung.native.ios.handoffExpiresAt";
    private static final long START_TTL_MILLIS = 600_000L;
    private static final long HANDOFF_TTL_SECONDS = 120L;
    private static final Pattern PKCE_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    @SuppressWarnings("rawtypes")
    private final ObjectProvider<SessionRepository> sessionRepositoryProvider;
    private final MemberService memberService;
    private final Clock clock;
    private final Object[] locks = createLocks(64);

    @SuppressWarnings("rawtypes")
    public NativeAuthHandoffService(
            ObjectProvider<SessionRepository> sessionRepositoryProvider,
            MemberService memberService,
            Clock clock) {
        this.sessionRepositoryProvider = sessionRepositoryProvider;
        this.memberService = memberService;
        this.clock = clock;
    }

    private static Object[] createLocks(int count) {
        Object[] result = new Object[count];
        for (int i = 0; i < count; i++) result[i] = new Object();
        return result;
    }

    public String validateChallenge(String challenge) {
        if (challenge == null || !PKCE_TOKEN.matcher(challenge).matches()) {
            throw new ApiException(400, "INVALID_AUTH_REQUEST", "로그인 요청 형식을 확인해주세요.");
        }
        return challenge;
    }

    public void rememberStart(HttpServletRequest request, String challenge) {
        String checked = validateChallenge(challenge);
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_CHALLENGE_ATTR, checked);
        session.setAttribute(SESSION_CHALLENGE_AT_ATTR, clock.millis());
    }

    public String consumeFailureCallback(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object rawChallenge = session.getAttribute(SESSION_CHALLENGE_ATTR);
        session.removeAttribute(SESSION_CHALLENGE_ATTR);
        session.removeAttribute(SESSION_CHALLENGE_AT_ATTR);
        if (!(rawChallenge instanceof String challenge) || !PKCE_TOKEN.matcher(challenge).matches()) {
            return null;
        }
        session.invalidate();
        return NATIVE_CALLBACK_PREFIX + "?status=failed";
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public String issueHandoffOrNull(HttpServletRequest request, Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object rawChallenge = session.getAttribute(SESSION_CHALLENGE_ATTR);
        Object rawStartedAt = session.getAttribute(SESSION_CHALLENGE_AT_ATTR);
        session.removeAttribute(SESSION_CHALLENGE_ATTR);
        session.removeAttribute(SESSION_CHALLENGE_AT_ATTR);
        if (!(rawChallenge instanceof String challenge) || !PKCE_TOKEN.matcher(challenge).matches()) {
            return null;
        }
        session.invalidate();
        if (!(rawStartedAt instanceof Long startedAt) || clock.millis() - startedAt > START_TTL_MILLIS) {
            return NATIVE_CALLBACK_PREFIX + "?status=failed";
        }
        UUID memberId = AccountIdentity.memberOrNull(authentication);
        if (memberId == null) {
            return NATIVE_CALLBACK_PREFIX + "?status=failed";
        }
        SessionRepository repository = requireRepository();
        Session handoff = repository.createSession();
        handoff.setMaxInactiveInterval(Duration.ofSeconds(HANDOFF_TTL_SECONDS));
        handoff.setAttribute(HANDOFF_MEMBER_ID, memberId.toString());
        handoff.setAttribute(HANDOFF_CHALLENGE, challenge);
        handoff.setAttribute(HANDOFF_EXPIRES_AT, clock.millis() + HANDOFF_TTL_SECONDS * 1000L);
        repository.save(handoff);
        return NATIVE_CALLBACK_PREFIX + "?code=" + handoff.getId();
    }

    @SuppressWarnings("rawtypes")
    public Member exchange(String code, String verifier) {
        if (code == null || code.isBlank() || verifier == null || !PKCE_TOKEN.matcher(verifier).matches()) {
            throw invalidHandoff();
        }
        try {
            UUID.fromString(code);
        } catch (IllegalArgumentException exception) {
            throw invalidHandoff();
        }
        SessionRepository repository = requireRepository();
        Session handoff;
        synchronized (locks[Math.floorMod(code.hashCode(), locks.length)]) {
            handoff = repository.findById(code);
            if (handoff != null) {
                repository.deleteById(code);
            }
        }
        if (handoff == null || handoff.isExpired()) {
            throw invalidHandoff();
        }
        Object rawMemberId = handoff.getAttribute(HANDOFF_MEMBER_ID);
        Object rawChallenge = handoff.getAttribute(HANDOFF_CHALLENGE);
        Object rawExpiresAt = handoff.getAttribute(HANDOFF_EXPIRES_AT);
        if (!(rawMemberId instanceof String memberIdText)
                || !(rawChallenge instanceof String expectedChallenge)
                || !(rawExpiresAt instanceof Long expiresAt)
                || expiresAt <= clock.millis()) {
            throw invalidHandoff();
        }
        String computedChallenge = sha256Base64Url(verifier);
        if (!MessageDigest.isEqual(
                computedChallenge.getBytes(StandardCharsets.US_ASCII),
                expectedChallenge.getBytes(StandardCharsets.US_ASCII))) {
            throw invalidHandoff();
        }
        try {
            return memberService.current(UUID.fromString(memberIdText));
        } catch (IllegalArgumentException exception) {
            throw invalidHandoff();
        }
    }

    @SuppressWarnings("rawtypes")
    private SessionRepository requireRepository() {
        SessionRepository repository = sessionRepositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("SessionRepository is unavailable");
        }
        return repository;
    }

    private static String sha256Base64Url(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static ApiException invalidHandoff() {
        return new ApiException(401, "AUTH_HANDOFF_INVALID", "로그인 확인 정보가 만료되었거나 올바르지 않아요. 다시 로그인해주세요.");
    }
}
