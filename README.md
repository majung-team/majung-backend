<div align="center">
  <h1>마중 백엔드 (Majung Backend)</h1>
  <p>차량 앱(AAOS)과 모바일 클라이언트(웹·iOS) 사이에서 운전자 계정, 차량 연결, 픽업 초대·요청, 장소 변경, 실시간 위치·ETA 공유를 관리하는 Spring Boot API 서버다.</p>
  <p>
    <a href="https://github.com/majung-team/majung">마중 프로젝트</a> ·
    <a href="https://github.com/majung-team/majung/wiki">Wiki</a> ·
    <a href="https://majung.cloud">모바일 웹</a> ·
    <a href="https://majung.cloud/api/swagger-ui/index.html">Swagger UI</a> ·
    <a href="https://majung.cloud/api/v1/health">API Health</a> ·
    <a href="https://github.com/majung-team/majung/blob/main/docs/development-status.md">개발 현황</a>
  </p>
</div>

> 서버는 내비게이션 경로나 교통 상황을 직접 계산하지 않는다. 경로와 예상 도착 시간(ETA)은 차량의 Pleos NaviHelper SDK가 제공하는 값을 수신해 유효성과 접근 권한을 검증한다.

---

## 1. 주요 기능

- **차량 등록 · 운전자 연결**: 차량 QR 요청 → 카카오 로그인 운전자 확인 → 차량 최종 승인 및 접근 권한 발급·복구·철회
  - 일반 최초 연결에 관리자 등록 코드 불필요(내부 관리·시험 절차와 분리) · 서비스 접근 권한 설정이며 차량 소유권 인증과는 별개
- **계정 · 사람 연결**: 카카오 OAuth2 로그인(웹 및 iOS PKCE `/api/v1/auth/ios/login`·`/exchange`), 닉네임·마중 ID 관리, 사용자 검색·연결 승인/거절, 세션 관리, 회원 탈퇴(`DELETE /api/v1/accounts/me`)
  - 사전에 연결되지 않은 운전자에게도 검색 후 픽업 요청 가능
- **픽업 초대 · 대기열**: 픽업 생성 및 초대 토큰(30분) 발급, 참여 전 유효 여부·장소 확인(`invitation-preview`, 링크 미소진), 비로그인 참여 및 대기 이탈(`POST /api/v1/pickups/{id}/leave`), 요청 대기열·실시간 목록 SSE(`GET /api/v1/ride-requests/events`)
  - 별도 회원가입/이름 입력 없는 게스트 참여 지원 · 차량당 진행 중 픽업 1건 유지, 타 픽업 위치·ETA 노출 차단
- **위치 · ETA · 소통**: Pleos SDK 위치·ETA 검증(SDK/서버 시각·전송 순번·목적지 일치·종료 후 차단), 준비 상태·3종 퀵 메시지(`POST .../quick-message`, 스냅샷 `quickMessage`·`vehicleName` 반영, 차량 TTS 낭독), Web Push(VAPID)·APNs 도착 임박 알림
- **장소 검색 · 변경 · 도착**: 한국 장소·주소 검색 프록시(`GET /api/v1/places/search`, 카카오 키워드·주소 병합/중복 제거, 키 미설정 시 `{enabled:false}`), 장소 변경 제안(내비 SDK 검증·확정 전 기존 장소 유지), 반경 100m 자동 도착 또는 수동 도착/완료·취소·만료
  - ETA 0만으로 자동 탑승 완료 처리하지 않으며 운전자의 완료 확인 후 위치 공유 종료
- **다중 픽업 계획**: `/api/v1/pickup-plans` 기반 경유지 추가/삭제(`stops`), TSP 순열 최단 경로 최적화(`optimize`, 5개 이하 경유지), 1회 확정(`confirm`), 탑승자별 ETA 조회(`GET .../eta`), 경유지별 완료(`complete`)·계획 취소(`cancel`)

---

## 2. 시스템 구성 및 기술 스택

```text
차량 앱 (AAOS · Kotlin) ──HTTPS(위치·ETA·상태)──▶ Spring Boot API ──▶ PostgreSQL
                                                       │
모바일 웹 / iOS (HTTPS) ◀────── SSE 변경 신호 ─────────┘ (수신·재접속 시 API 재조회)
```

- **아키텍처**: `src/main/java/app/majung/server/` 아래 `MajungServerApplication`과 `pickup/` 도메인 패키지(`api` · `service` · `domain` · `repository` · `security` · `job` · `push` · `common`)로 계층 분리되어 있으며, `src/test/java/app/majung/server/pickup/`가 1:1로 대응한다.
- **실시간 동기화**: SSE는 상태 변경 신호만 전달하며, 클라이언트는 신호 수신 또는 재연결 시 필요한 API를 다시 조회해 최신 상태를 맞춘다.

| 구분 | 구성 |
| --- | --- |
| **백엔드** | Java 17 컴파일(JDK 25 런타임) · Spring Boot 4.0.8 · Spring MVC · Spring Security OAuth2 Client · Spring Data JPA |
| **데이터 · 세션** | PostgreSQL(운영) · H2(로컬/테스트) · Spring Session JDBC(`SPRING_SESSION`) |
| **알림 · API 문서** | Server-Sent Events(SSE) · Web Push(VAPID) · Apple APNs · springdoc OpenAPI (Swagger UI) |
| **인프라 · 배포** | Caddy(HTTPS Reverse Proxy) · GitHub Actions · GCP VM (단일 VM Blue-Green) |

---

## 3. 로컬 개발 환경

**요구 환경**: JDK 25 · Gradle Wrapper · 로컬 환경변수/DB 설정(`src/main/resources/`, `deploy/server.env.example` 참고 — 운영 키·DB 비밀번호 저장소 작성 금지)

```sh
git clone https://github.com/majung-team/majung-backend.git
cd majung-backend

# 테스트 및 JAR 빌드
./gradlew test bootJar

# 로컬 서버 실행 (실행 전 DB, Spring 프로파일, OAuth2 로그인 설정, 포트 확인)
./gradlew bootRun
```

| 항목 | 로컬 개발 주소 | 운영 서버 주소 |
| --- | --- | --- |
| **서비스 API 루트** | `http://127.0.0.1:8080` | `https://majung.cloud` |
| **Swagger UI** | `http://127.0.0.1:8080/api/swagger-ui/index.html` | `https://majung.cloud/api/swagger-ui/index.html` |
| **Health Check** | `http://127.0.0.1:8080/api/v1/health` | `https://majung.cloud/api/v1/health` |

> 로컬에서는 서버와 모바일 웹을 별도로 실행한다. 웹 실행 방법은 [majung-web](https://github.com/majung-team/majung-web)을 참고한다.

---

## 4. API 기준 · 보안 및 검증 범위

- **접근 권한 분리**: 모든 엔드포인트는 `/api/v1/`를 사용하며 웹 로그인 세션, 차량 등록·접근 권한, 픽업 운전자 권한, 초대·탑승자 권한을 구분해 요청 대상과 현재 상태를 검증한다.
- **위치 최신성과 데이터 파기**: 차량 SDK 수신 시각, 서버 수신 시각, 전송 순번, 목적지·장소 버전을 함께 확인해 전송 시각만 갱신된 오래된 GPS를 최신값으로 취급하지 않으며, 완료·취소·만료 후에는 위치 접근을 차단한다([픽업 생명주기](docs/pickup-lifecycle.md)).

| 구분 | 범위 |
| --- | --- |
| **자동화 검증 범위** | 차량·운전자 계정 연결, 픽업 초대·참여·요청 대기열, 위치·ETA 유효성, 상태 전환·종료, Blue-Green 후보 서버·공유 세션·배포 실패 복구 경로 |
| **실환경 별도 확인** | 실제 휴대전화 간 전체 픽업 흐름, iPhone 등 실기기 푸시 배너 수신, LTE·Wi-Fi 네트워크 전환, 실차 SDK 장시간 연동 및 시동 OFF/ON 복구 |

> 코드 구현 및 자동화 테스트 통과를 실차·실기기 통합 검증 완료로 간주하지 않으며, 기능별 시험 근거는 [개발 현황](https://github.com/majung-team/majung/blob/main/docs/development-status.md)에서 확인한다.

---

## 5. 운영 환경 · Blue-Green 배포

운영 환경에서는 Caddy가 `https://majung.cloud` HTTPS 요청을 받아 `/api/`는 Spring Boot 서버로, 정적 파일은 웹으로 분기한다. `curl -fsS https://majung.cloud/api/v1/health` 응답의 `status`, `revision`(배포 커밋), `slot`(`standalone` · `blue` · `green`)으로 현재 상태를 확인한다.

- **전환 흐름**: 단일 GCP VM 내 `Blue(8081)` · `Green(8082)` 슬롯 중 비활성 슬롯에 새 JAR 배포 → DB·세션·커밋 SHA·슬롯 점검 → Caddy 업스트림 전환 → 공개 HTTPS 확인 → 정기 작업(`job`) 슬롯 전환 → 이전 서버 안전 종료(실패 시 기존 슬롯으로 복구).
- **공유 세션과 정기 작업**: 두 슬롯이 PostgreSQL `SPRING_SESSION` · `SPRING_SESSION_ATTRIBUTES`를 공유해 전환 중 로그인·CSRF 세션을 유지하며, 만료·데이터 정리 작업은 활성 슬롯에서만 실행한다.
- **운영상 한계 및 DB 주의**: 단일 VM 내부 전환이므로 VM/PostgreSQL 이중화를 대체하지 않으며, 전환 중 끊긴 SSE는 클라이언트가 재접속 후 재조회해야 한다. 또한 이전 JAR 롤백이 DB 스키마를 되돌리지 않으므로, 컬럼 삭제·이름 변경 같은 비호환 변경은 앱 배포 전에 역호환성과 백업·권한을 별도 검토한다.

상세 운영 절차: [Blue-Green 배포](docs/blue-green.md) · [GitHub Actions 배포](docs/github-actions.md) · [백업 복원·운영 점검](docs/operations-recovery.md) · [복구·ETA·권한 검증](docs/stability-verification.md)

---

## 6. 상세 문서 · 관련 저장소 · 개발 참여

| 분류 | 문서 |
| --- | --- |
| **계정 · 차량** | [차량 QR 최초 연결](docs/vehicle-enrollment.md) · [차량 계정 연결](docs/vehicle-account-link.md) · [내부 등록 및 권한 철회](docs/vehicle-registration.md) · [계정 및 사람 검색](docs/account-connections.md) |
| **픽업 · 공유** | [픽업 요청 및 대기열](docs/ride-requests.md) · [차량 위치 최신성](docs/vehicle-state.md) · [도착 임박 푸시](docs/arrival-push.md) · [장소 변경 제안](docs/place-proposal.md) · [픽업 생명주기](docs/pickup-lifecycle.md) |
| **운영 · 개발** | [GitHub Actions 배포](docs/github-actions.md) · [Blue-Green 배포](docs/blue-green.md) · [현재 개발 기준](docs/revised-plan.md) · [기여 가이드](CONTRIBUTING.md) |

| 저장소 | 역할 |
| --- | --- |
| [majung](https://github.com/majung-team/majung) | 전체 서비스 소개 · 설계 · 시연 |
| [majung-vehicle-app](https://github.com/majung-team/majung-vehicle-app) | AAOS 차량 앱 및 Pleos SDK 연동 |
| [majung-web](https://github.com/majung-team/majung-web) | 운전자 · 탑승자 모바일 웹 |
| [majung-backend](https://github.com/majung-team/majung-backend) | Spring Boot API 및 운영 배포 |

기여 시 [기여 가이드](CONTRIBUTING.md)를 따르며, 로컬 커밋 메시지 검사 도구는 `npm ci`로 설치한다. 기능 변경 시 관련 이슈를 확인하고 실제 실행한 테스트 결과와 남은 검증 항목을 PR에 기록한다.
