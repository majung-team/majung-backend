package app.majung.server.pickup.api;

import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "07. 회원 계정 (Accounts)", description = "로그인 세션 확인, 닉네임 설정, 회원 검색 및 상호 연결")
@RestController
public class AccountApi {
    public record Profile(
            @Schema(description = "회원 고유 식별자 (UUID)", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890") UUID memberId,
            @Schema(description = "마중 고유 아이디", example = "majung_pilot") String majungId,
            @Schema(description = "사용자 닉네임", example = "홍길동") String nickname,
            @Schema(description = "프로필 설정 완료 여부", example = "true") boolean profileReady
    ) { }

    public record SearchResult(
            @Schema(description = "회원 식별자 (UUID)", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890") UUID memberId,
            @Schema(description = "마중 고유 아이디", example = "driver_kim") String majungId,
            @Schema(description = "사용자 닉네임", example = "김운전") String nickname
    ) { }

    public record Connection(
            @Schema(description = "연결 고유 식별자 (UUID)", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479") UUID connectionId,
            @Schema(description = "상대방 마중 아이디", example = "passenger_lee") String majungId,
            @Schema(description = "상대방 닉네임", example = "이탑승") String nickname,
            @Schema(description = "연결 상태 (PENDING, CONNECTED)", example = "CONNECTED") String status,
            @Schema(description = "연결 방향 (INCOMING, OUTGOING)", example = "OUTGOING") String direction
    ) { }

    public record Nickname(
            @Schema(description = "새로운 닉네임 (최대 80자)", example = "멋진드라이버") @NotBlank @Size(max = 80) String nickname
    ) { }

    public record Target(
            @Schema(description = "연결할 대상 회원 식별자 (UUID)", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890") UUID memberId
    ) { }

    public record Session(
            @Schema(description = "로그인 기능 활성화 여부", example = "true") boolean loginEnabled,
            @Schema(description = "현재 인증(로그인) 여부", example = "true") boolean authenticated,
            @Schema(description = "로그인된 회원 프로필 정보 (비로그인 시 null)") Profile member,
            @Schema(description = "CSRF 토큰", example = "a8f3b2c1-d4e5-4678-90ab-cdef12345678") String csrfToken
    ) { }

    private final MemberService memberService;
    private final boolean enabled;

    public AccountApi(MemberService memberService, @Value("${majung.login.enabled:false}") boolean enabled) {
        this.memberService = memberService;
        this.enabled = enabled;
    }

    @Operation(summary = "로그인 세션 상태 조회", description = "현재 브라우저/클라이언트의 로그인 상태, 인증된 회원 프로필 및 CSRF 토큰을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세션 정보 조회 성공")
    })
    @GetMapping("/api/v1/auth/session")
    Session session(@Parameter(hidden = true) Authentication authentication, @Parameter(hidden = true) CsrfToken csrf) {
        UUID id = AccountIdentity.memberOrNull(authentication);
        return new Session(enabled, id != null, id == null ? null : MemberService.profile(memberService.current(id)), csrf.getToken());
    }

    @Operation(summary = "카카오 로그인 리다이렉트", description = "카카오 OAuth2 인가 코드 요청 주소로 302 리다이렉트합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "카카오 로그인 페이지로 리다이렉트"),
            @ApiResponse(responseCode = "503", description = "로그인 서비스가 설정되지 않음")
    })
    @GetMapping("/api/v1/auth/login")
    void login(HttpServletResponse response) throws IOException {
        if (!enabled) throw new ApiException(503, "LOGIN_NOT_CONFIGURED", "로그인 준비 중이에요.");
        response.setStatus(302);
        response.setHeader("Location", "/api/v1/auth/authorize/kakao");
    }

    @Operation(summary = "내 프로필 조회", description = "현재 로그인한 사용자의 프로필 정보(회원 식별자, 마중 아이디, 닉네임 등)를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "프로필 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자 (로그인 필요)")
    })
    @GetMapping("/api/v1/accounts/me")
    Profile me(@Parameter(hidden = true) Authentication authentication) {
        return MemberService.profile(memberService.current(AccountIdentity.requireMember(authentication)));
    }

    @Operation(summary = "내 닉네임 변경", description = "로그인한 사용자의 닉네임을 변경합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "닉네임 변경 성공"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 닉네임 형식"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @PatchMapping("/api/v1/accounts/me")
    Profile nickname(@Parameter(hidden = true) Authentication authentication, @Valid @RequestBody Nickname request) {
        return memberService.nickname(AccountIdentity.requireMember(authentication), request.nickname());
    }

    @Operation(summary = "회원 검색", description = "마중 아이디 또는 닉네임으로 다른 회원을 검색합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검색 결과 반환 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @GetMapping("/api/v1/accounts/search")
    List<SearchResult> search(@Parameter(hidden = true) Authentication authentication,
                              @Parameter(description = "검색 키워드 (아이디 또는 닉네임)") @RequestParam String query,
                              @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page) {
        return memberService.search(AccountIdentity.requireMember(authentication), query, page);
    }

    @Operation(summary = "내 연결(자주 타는 사람/운전자) 목록 조회", description = "나와 연결되어 있거나 요청 대기 중인 회원 목록을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "연결 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @GetMapping("/api/v1/connections")
    List<Connection> connections(@Parameter(hidden = true) Authentication authentication,
                                 @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page) {
        return memberService.connections(AccountIdentity.requireMember(authentication), page);
    }

    @Operation(summary = "회원 연결 요청 전송", description = "특정 회원에게 자주 타는 사람/운전자 연결을 요청합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "연결 요청 성공"),
            @ApiResponse(responseCode = "400", description = "대상 미지정 또는 잘못된 요청"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @PostMapping("/api/v1/connections")
    Connection request(@Parameter(hidden = true) Authentication authentication, @RequestBody Target target) {
        if (target.memberId() == null) throw new ApiException(400, "TARGET_REQUIRED", "연결할 사람을 선택해주세요.");
        return memberService.request(AccountIdentity.requireMember(authentication), target.memberId());
    }

    @Operation(summary = "연결 요청 수락/거절/해제", description = "수신한 연결 요청을 수락, 거절하거나 기존 연결을 해제합니다. (action: accept, reject, delete)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "연결 상태 변경 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "404", description = "해당 연결을 찾을 수 없음")
    })
    @PostMapping("/api/v1/connections/{id}/{action}")
    Connection change(@Parameter(hidden = true) Authentication authentication,
                      @Parameter(description = "연결 고유 식별자 (UUID)") @PathVariable UUID id,
                      @Parameter(description = "수행할 작업 (accept, reject, delete)") @PathVariable String action) {
        return memberService.change(AccountIdentity.requireMember(authentication), id, action);
    }
}
