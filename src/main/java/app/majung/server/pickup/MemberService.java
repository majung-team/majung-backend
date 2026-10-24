package app.majung.server.pickup;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class MemberService {
    private final MemberRepository memberRepository;
    private final MemberConnectionRepository memberConnectionRepository;
    private final KakaoUnlinkRepository unlinkRepository;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    MemberService(MemberRepository memberRepository, MemberConnectionRepository memberConnectionRepository,
                  KakaoUnlinkRepository unlinkRepository, Clock clock, PlatformTransactionManager transactionManager) {
        this.memberRepository = memberRepository;
        this.memberConnectionRepository = memberConnectionRepository;
        this.unlinkRepository = unlinkRepository;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // The provider identity comes only from the server-side OAuth user-info response.
    Member login(String subject) {
        if (subject == null || !subject.matches("[0-9]{1,30}"))
            throw new ApiException(502, "LOGIN_IDENTITY_INVALID", "로그인 정보를 확인하지 못했어요.");
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return transaction.execute(status -> {
                    requireLinked(subject);
                    return memberRepository.findByProviderAndProviderSubject("kakao", subject)
                        .orElseGet(() -> {
                            byte[] bytes = new byte[5];
                            random.nextBytes(bytes);
                            Member member = new Member();
                            member.id = UUID.randomUUID();
                            member.provider = "kakao";
                            member.providerSubject = subject;
                            member.majungId = "MJ-" + HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
                            member.nickname = "새 회원";
                            member.normalizedNickname = "새 회원";
                            member.profileReady = false;
                            member.createdAt = clock.instant();
                            return memberRepository.saveAndFlush(member);
                        });
                });
            } catch (DataIntegrityViolationException conflict) {
                // Transaction has rolled back. Retry an ID collision or concurrent first login.
            }
        }
        throw new ApiException(503, "LOGIN_RETRY_REQUIRED", "가입 처리 중이에요. 잠시 후 다시 로그인해주세요.");
    }

    @Transactional(readOnly = true)
    Member current(UUID id) {
        Member member = memberRepository.findById(id).orElseThrow(() ->
                new ApiException(401, "LOGIN_REQUIRED", "다시 로그인해주세요."));
        requireLinked(member.providerSubject);
        return member;
    }

    @Transactional
    AccountApi.Profile nickname(UUID id, String input) {
        Member member = memberRepository.lockById(id).orElseThrow(() ->
                new ApiException(401, "LOGIN_REQUIRED", "다시 로그인해주세요."));
        requireLinked(member.providerSubject);
        String normalized = Normalizer.normalize(input.strip(), Normalizer.Form.NFKC);
        if (!normalized.matches("[\\p{L}\\p{N} _.-]{2,20}"))
            throw new ApiException(400, "NICKNAME_INVALID", "닉네임은 문자·숫자·공백·밑줄·점·하이픈으로 2~20자 입력해주세요.");
        member.nickname = normalized;
        member.normalizedNickname = normalized.toLowerCase(Locale.ROOT);
        member.profileReady = true;
        return profile(member);
    }

    @Transactional(readOnly = true)
    List<AccountApi.SearchResult> search(UUID self, String input, int page) {
        requireReady(current(self));
        String query = Normalizer.normalize(input.strip(), Normalizer.Form.NFKC);
        if (query.length() < 2 || query.length() > 32 || page < 0 || page > 50)
            throw new ApiException(400, "SEARCH_INVALID", "검색어는 2~32자로 입력해주세요.");
        String escaped = query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return memberRepository.search(self, "%" + escaped + "%", query.toUpperCase(Locale.ROOT), PageRequest.of(page, 20))
                .stream().map(member -> new AccountApi.SearchResult(member.id, member.majungId, member.nickname)).toList();
    }

    @Transactional
    AccountApi.Connection request(UUID self, UUID target) {
        if (self.equals(target)) throw new ApiException(400, "SELF_CONNECTION", "본인에게 연결 요청을 보낼 수 없어요.");
        // Lock member rows in a stable order: duplicate/opposite-direction requests serialize.
        UUID first = self.compareTo(target) < 0 ? self : target;
        UUID second = first.equals(self) ? target : self;
        Member firstMember = memberRepository.lockById(first).orElseThrow(MemberService::notFound);
        Member secondMember = memberRepository.lockById(second).orElseThrow(MemberService::notFound);
        requireLinked(firstMember.providerSubject);
        requireLinked(secondMember.providerSubject);
        requireReady(firstMember);
        requireReady(secondMember);
        MemberConnection connection = memberConnectionRepository.findByFirstMemberAndSecondMember(first, second).orElse(null);
        if (connection != null && (connection.status.equals("PENDING") || connection.status.equals("ACCEPTED")))
            return connection(connection, self);
        if (connection == null) {
            connection = new MemberConnection();
            connection.id = UUID.randomUUID();
            connection.firstMember = first;
            connection.secondMember = second;
        } else if (connection.updatedAt.plusSeconds(86400).isAfter(clock.instant())) {
            throw new ApiException(409, "CONNECTION_COOLDOWN", "이 연결에는 잠시 후 다시 요청할 수 있어요.");
        }
        connection.requester = self;
        connection.recipient = target;
        connection.status = "PENDING";
        connection.createdAt = clock.instant();
        connection.updatedAt = clock.instant();
        return connection(memberConnectionRepository.save(connection), self);
    }

    @Transactional
    AccountApi.Connection change(UUID self, UUID id, String action) {
        current(self);
        MemberConnection connection = memberConnectionRepository.lockById(id).orElseThrow(MemberService::notFound);
        boolean recipient = connection.recipient.equals(self);
        boolean requester = connection.requester.equals(self);
        if (!recipient && !requester) throw notFound();
        String next;
        switch (action) {
            case "accept", "reject" -> {
                if (!recipient) throw new ApiException(403, "RECIPIENT_REQUIRED", "요청받은 사람만 처리할 수 있어요.");
                next = action.equals("accept") ? "ACCEPTED" : "REJECTED";
                if (!connection.status.equals(next) && !connection.status.equals("PENDING")) throw stateConflict();
            }
            case "cancel" -> {
                if (!requester) throw new ApiException(403, "REQUESTER_REQUIRED", "보낸 요청만 취소할 수 있어요.");
                next = "CANCELLED";
                if (!connection.status.equals(next) && !connection.status.equals("PENDING")) throw stateConflict();
            }
            case "disconnect" -> {
                next = "DISCONNECTED";
                if (!connection.status.equals(next) && !connection.status.equals("ACCEPTED")) throw stateConflict();
            }
            default -> throw new ApiException(400, "CONNECTION_ACTION_INVALID", "연결 처리 방법을 확인해주세요.");
        }
        connection.status = next;
        connection.updatedAt = clock.instant();
        return connection(connection, self);
    }

    @Transactional(readOnly = true)
    List<AccountApi.Connection> connections(UUID self, int page) {
        current(self);
        if (page < 0 || page > 50) throw new ApiException(400, "PAGE_INVALID", "페이지를 확인해주세요.");
        return memberConnectionRepository.listVisible(self, PageRequest.of(page, 20)).stream()
                .map(connection -> connection(connection, self)).toList();
    }

    private AccountApi.Connection connection(MemberConnection connection, UUID self) {
        Member other = current(connection.requester.equals(self) ? connection.recipient : connection.requester);
        return new AccountApi.Connection(connection.id, other.majungId, other.nickname,
                connection.status, connection.requester.equals(self) ? "OUTGOING" : "INCOMING");
    }
    static AccountApi.Profile profile(Member member) {
        return new AccountApi.Profile(member.id, member.majungId, member.nickname, member.profileReady);
    }
    private void requireLinked(String subject) {
        if (unlinkRepository.existsById(subject))
            throw new ApiException(401, "LOGIN_REQUIRED", "카카오 연결이 해제됐어요. 잠시 후 다시 로그인해주세요.");
    }
    private static void requireReady(Member member) {
        if (!member.profileReady) throw new ApiException(409, "PROFILE_REQUIRED", "먼저 닉네임을 설정해주세요.");
    }
    private static ApiException notFound() { return new ApiException(404, "CONNECTION_NOT_FOUND", "연결 대상을 찾을 수 없어요."); }
    private static ApiException stateConflict() { return new ApiException(409, "CONNECTION_STATE_CONFLICT", "연결 상태가 바뀌었어요. 다시 확인해주세요."); }
}
