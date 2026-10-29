# majung-backend

마중의 Spring Boot 서버다. 차량과 휴대전화 사이에서 계정·초대·픽업·장소·준비 상태를 관리한다.
위치·ETA는 차량 SDK에서 받은 값을 검사해 제공하며 서버가 경로나 교통 상황을 계산하지 않는다.

[전체 사용 안내](https://github.com/majung-team/majung/wiki) · [현재 개발·PR 근거](https://github.com/majung-team/majung/blob/main/docs/development-status.md)

## 접속·실행

- 공개 웹·API: https://majung.cloud
- 상태: https://majung.cloud/api/v1/health (`status`, 배포 `revision` 확인)
- 요구 환경: JDK 25

```sh
./gradlew test bootJar
./gradlew bootRun
```

로컬 API는 `127.0.0.1:8080`이다. 웹은 별도 저장소에서 실행한다.
운영에서는 Caddy가 같은 도메인의 `/api/`를 서버에 전달한다.
키·DB 비밀번호·관리자 권한은 서버 설정에서 관리하며 저장소에 넣지 않는다.
설정 예시는 `deploy/`와 `src/main/resources/`를 본다.

## 기능·운영 문서

| 문서 | 내용 |
| --- | --- |
| [차량 QR 최초 연결](docs/vehicle-enrollment.md) | 일반 운전자 시작 방법. 관리자 코드 입력 불필요 |
| [차량 계정 연결](docs/vehicle-account-link.md) | 이미 등록한 차량의 운전자 계정 연결 |
| [내부 등록·권한 철회](docs/vehicle-registration.md) | 관리자 코드·차량 권한 관리 |
| [계정·사람 검색](docs/account-connections.md) | 로그인·닉네임·세션·사람 연결 |
| [요청·대기열](docs/ride-requests.md) | 여러 요청 수락, 한 건씩 픽업 시작 |
| [위치 최신성](docs/vehicle-state.md) | 자료 시각·유효 시간·끊김 처리 |
| [도착 임박 푸시](docs/arrival-push.md) | 구독·VAPID·도착 임박 알림 |
| [장소 제안](docs/place-proposal.md) | 제안·차량 경로 검증·장소 버전 확정 |
| [자동 종료·파기](docs/pickup-lifecycle.md) | 종료 기준과 보관·파기 |
| [Actions 배포](docs/github-actions.md) | 테스트 후 배포, 실패 시 이전 코드 복구 |
| [블루·그린 배포](docs/blue-green.md) | 두 서버 전환, 공유 세션, 최초 설치와 복구 |

SSE는 웹에 변경 신호를 보내고 웹이 현재 상태를 다시 조회한다.
블루·그린을 설치한 서버에서는 후보 서버 확인 후 Caddy 연결을 전환한다.
최초 설치 전에는 기존 재시작 배포를 사용한다. 실제 적용 여부는 공개 health의 `slot`으로 확인한다.
`standalone`은 기존 방식, `blue`·`green`은 두 서버 전환 방식이다.
DB 변경은 백업·마이그레이션·권한을 먼저 확인하며 코드 복구가 DB 변경까지 되돌리지는 않는다.

실제 휴대전화 전체 흐름·푸시 배너·LTE 전환·실차 검증은 별도다.
현재 담당·남은 작업은 [개발 기준](docs/revised-plan.md)을 본다.
커밋 검사 훅은 `npm ci`로 설치하며 [기여 규칙](CONTRIBUTING.md)을 따른다.
