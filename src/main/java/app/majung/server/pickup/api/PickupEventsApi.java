package app.majung.server.pickup.api;

import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "08. 실시간 이벤트 SSE (Pickup Events)", description = "픽업 및 마중 상태 변경 실시간 서버 전송 이벤트(Server-Sent Events) 스트림 API")
@RestController
public class PickupEventsApi {
    private final PickupService pickups;
    private final RideRequestService rides;
    private final PickupEventStreams streams;

    public PickupEventsApi(PickupService pickups, RideRequestService rides, PickupEventStreams streams) {
        this.pickups = pickups;
        this.rides = rides;
        this.streams = streams;
    }

    private static void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
    }

    @Operation(summary = "픽업 실시간 SSE 스트림 연결 (게스트/토큰)", description = "해당 픽업 세션의 상태 변경(참여, 준비 상태, 장소 변경, 빠른 메시지, 도착, 종료 등)을 실시간으로 수신하는 SSE 연결을 수립합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 스트림 연결 성공 (text/event-stream)"),
            @ApiResponse(responseCode = "404", description = "픽업 세션을 찾을 수 없음")
    })
    @GetMapping(value = "/api/v1/pickups/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter guest(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                     @Parameter(description = "운전자 또는 승객 인증 토큰") @RequestHeader(value = "Authorization", required = false) String auth,
                     @Parameter(hidden = true) HttpServletResponse response) {
        headers(response);
        return streams.open(id, () -> pickups.streamState(id, auth));
    }

    @Operation(summary = "마중 요청 실시간 SSE 스트림 연결 (회원 전용)", description = "로그인된 회원의 마중 요청 상태 변화 및 대기열 변경 이벤트를 실시간으로 수신하는 SSE 연결을 수립합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 스트림 연결 성공 (text/event-stream)"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자 (로그인 필요)")
    })
    @GetMapping(value = "/api/v1/ride-requests/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter account(@Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id,
                       @Parameter(hidden = true) Authentication authentication,
                       @Parameter(hidden = true) HttpServletRequest request,
                       @Parameter(hidden = true) HttpServletResponse response) {
        UUID member = requireSessionMember(authentication, request);
        HttpSession session = request.getSession(false);
        headers(response);
        return streams.open(id, () -> {
            verifyActiveSession(session, member);
            RideRequestApi.Tracking current = rides.tracking(member, id);
            return new PickupEventStreams.State(current.pickup(), current.vehicle());
        });
    }

    @Operation(summary = "내 요청·초대 목록 실시간 SSE 스트림 (회원 전용)", description = "받은 마중 초대, 수락/거절 결과, 대기열 변경 이벤트를 실시간으로 수신합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 스트림 연결 성공 (text/event-stream)"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자 (로그인 필요)")
    })
    @GetMapping(value = "/api/v1/ride-requests/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter accountRequests(@Parameter(hidden = true) Authentication authentication,
                                      @Parameter(hidden = true) HttpServletRequest request,
                                      @Parameter(hidden = true) HttpServletResponse response) {
        UUID member = requireSessionMember(authentication, request);
        HttpSession session = request.getSession(false);
        headers(response);
        return streams.openRevision(member, () -> {
            verifyActiveSession(session, member);
            return rides.passengerRevision(member);
        });
    }

    private static UUID requireSessionMember(Authentication authentication, HttpServletRequest request) {
        return AccountIdentity.requireMember(authentication);
    }

    private static void verifyActiveSession(HttpSession session, UUID member) {
        try {
            Object stored = session == null ? null : session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (!(stored instanceof SecurityContext context)
                    || !member.equals(AccountIdentity.memberOrNull(context.getAuthentication())))
                throw new ApiException(401, "LOGIN_REQUIRED", "다시 로그인해주세요.");
        } catch (IllegalStateException ended) {
            throw new ApiException(401, "LOGIN_REQUIRED", "다시 로그인해주세요.");
        }
    }
}
