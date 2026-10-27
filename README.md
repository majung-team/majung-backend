# majung-backend

**마중(Majung) 서비스의 Spring Boot 기반 REST API 및 실시간 데이터 동기화 백엔드 저장소입니다.**

차량(Pleos Android Automotive)과 탑승자(모바일 웹) 사이에서 픽업 세션, 실시간 위치·ETA 전송, 탑승자의 준비 상태 및 장소 제안을 안전하고 신속하게 중계합니다.

- **서비스 통합 안내**: [majung 메인 저장소](https://github.com/majung-team/majung)
- **운영 서버 주소**: https://majung.cloud
- **기획 명세**: [팀 노션 페이지](https://app.notion.com/p/4026da9ae26a805abd45c0ee5885ef2f)

## 실행 방법

- **요구사항**: JDK 25 이상
- **빌드 및 테스트**:
  ```bash
  ./gradlew test bootJar
  ```
- **로컬 실행**:
  ```bash
  ./gradlew bootRun
  ```
  기본적으로 `127.0.0.1:8080` 포트에서 API 서버가 시작됩니다.  
  웹 프론트엔드는 `majung-web` 저장소에서 독립적으로 실행되며, 배포 환경에서는 리버스 프록시(Caddy)를 통해 동일 도메인의 `/api/` 경로로 라우팅됩니다.  
  환경변수 및 설정 프로필은 `deploy/` 및 `src/main/resources/`를 참고해 주시기 바랍니다.

## 핵심 기능 및 상세 문서

- **[차량 최초 등록 및 권한 관리](docs/vehicle-registration.md)**: 등록 코드 발급, 차량별 개별 권한 인가 및 철회 절차
- **[카카오 계정 연동 및 닉네임 검색](docs/account-connections.md)**: 소셜 로그인, 세션/CSRF 보안 및 사용자 간 연결 API
- **[탑승자 픽업 요청 및 대기열](docs/ride-requests.md)**: QR 없는 탑승자 주도 요청, 다중 픽업 수락 및 순차 대기열 처리
- **[차량 연결 상태 모니터링](docs/vehicle-state.md)**: 통신 지연(30초) 및 단절(2분) 시 상태 처리 기준
- **[도착 임박 웹 푸시 알림](docs/arrival-push.md)**: 도착 3분 전 VAPID 기반 브라우저 푸시 알림 발송
- **[장소 제안 및 변경](docs/place-proposal.md)**: 탑승자의 장소 변경 제안 및 확정 동기화
- **[픽업 생명주기 및 데이터 정리](docs/pickup-lifecycle.md)**: 세션 자동 종료 및 보관 주기 정책
- **[CI/CD 및 배포 자동화](docs/github-actions.md)**: GitHub Actions 기반 무중단 배포 및 자동 롤백 파이프라인

## 협업 규칙

커밋 전 컨벤션 검사를 위해 `npm ci`를 실행하여 Git 훅을 설정합니다.  
차량·웹·서버 간 API 변경이 필요한 경우, [개정 명세 대조 및 작업 순서](docs/revised-plan.md)를 바탕으로 인터페이스를 사전에 조율한 뒤 작업을 진행합니다.
