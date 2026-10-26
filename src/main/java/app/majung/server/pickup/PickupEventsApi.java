package app.majung.server.pickup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class PickupEventsApi {
    private final PickupService pickups;
    private final RideRequestService rides;
    private final PickupEventStreams streams;
    PickupEventsApi(PickupService pickups, RideRequestService rides, PickupEventStreams streams) {
        this.pickups = pickups; this.rides = rides; this.streams = streams;
    }
    private static void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
    }
    @GetMapping(value = "/api/v1/pickups/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter guest(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth,
                     HttpServletResponse response) {
        headers(response);
        return streams.open(id, () -> pickups.streamState(id, auth));
    }
    @GetMapping(value = "/api/v1/ride-requests/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter account(@PathVariable UUID id, Authentication authentication, HttpServletRequest request,
                       HttpServletResponse response) {
        UUID member = AccountIdentity.requireMember(authentication);
        HttpSession session = request.getSession(false);
        headers(response);
        return streams.open(id, () -> {
            try {
                Object stored = session == null ? null : session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                if (!(stored instanceof SecurityContext context)
                        || !member.equals(AccountIdentity.memberOrNull(context.getAuthentication())))
                    throw new ApiException(401, "LOGIN_REQUIRED", "다시 로그인해주세요.");
            } catch (IllegalStateException ended) {
                throw new ApiException(401, "LOGIN_REQUIRED", "다시 로그인해주세요.");
            }
            RideRequestApi.Tracking current = rides.tracking(member, id);
            return new PickupEventStreams.State(current.pickup(), current.vehicle());
        });
    }
}
