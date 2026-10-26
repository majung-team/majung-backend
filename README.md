# majung-backend

마중 서버 API 저장소다.

기존 majung 저장소의 server/ 커밋 이력을 추출했다. 기존 main의 동작을 옮긴 것으로, 새 기능명세의 모든 기능을 완료한 상태는 아니다.

- 기획·진행 안내: https://github.com/majung-team/majung
- 기준 명세: https://app.notion.com/p/4026da9ae26a805abd45c0ee5885ef2f

## 실행

JDK 25로 `./gradlew test bootJar`를 실행한다. `./gradlew bootRun`으로 API를 127.0.0.1:8080에서 연다.
웹은 majung-web에서 따로 실행한다. 서버 JAR는 인접 웹 디렉터리를 읽지 않는다.
환경변수·deploy 프로필·스키마는 deploy/와 src/main/resources/를 확인한다.

## 차량 최초 등록

[등록 코드 발급·차량 권한·개별 철회 안내](docs/vehicle-registration.md)를 확인한다. 관리 공통 키로 픽업을 만드는 방식은 차량별 등록 권한으로 교체했다. 기존 배포 DB에는 스키마 업그레이드와 관리자 환경변수 변경이 필요하다.

## 협업

`npm ci`로 커밋 검사 훅을 설치한다. 성모·승준 모두 기능별로 서버·웹·차량 변경을 함께 맡는다.
각 저장소의 PR에서 관련 저장소 PR과 기능 번호를 연결한다. 다른 저장소의 준비가 필요하면 먼저 API 요청·응답을 합의한다.

[개정 명세 대조와 작업 순서](docs/revised-plan.md)를 확인한다. 아직 구현하지 않은 기능과 현재 기준 동작을 구분해 적었다.

[카카오 가입·닉네임 검색·연결 API](docs/account-connections.md): 서버 환경변수, 세션·CSRF, DB 업그레이드와 검증 범위.

[차량 정보 끊김 표시용 응답](docs/vehicle-state.md): 30초 이후 마지막 위치·ETA와 경과 시간, 2분 이후 숨김.

[탑승자 지도용 카카오맵 키](docs/kakao-map.md): 설정 응답, 서버 환경변수, 도메인 등록.

[탑승자 자동 연결과 끊기](docs/auto-connect.md): 이름·승인 없는 연결, 운전자의 탑승자 끊기와 재초대.

[도착 임박 웹 푸시](docs/arrival-push.md): 3분 전 알림 한 번, 구독 API, VAPID 키와 DB 적용.

## GitHub Actions

[테스트·배포·복구 흐름](docs/github-actions.md)을 참고한다. main의 검사 결과물을 마중 서버로 배포하며, 실제 Actions 검증 전에는 자동 배포를 꺼둔다. 앱 인증정보는 서버에만 보관한다.

## 탑승자가 먼저 보내는 요청

차량 QR이나 기존 연결 없이 운전자를 검색해 장소와 함께 요청한다. 운전자는 여러 요청을 수락해 대기에 넣고 현재 픽업을 마친 뒤 차례대로 시작한다. [사용 방법과 현재 검증 범위](docs/ride-requests.md)를 참고한다. 새 흐름의 공개 배포 상태는 PR 반영과 구분한다.

연결된 사람에게 차량에서 바로 보내는 초대는 [직접 초대 안내](docs/direct-invitation.md)를 참고한다.

[픽업 자동 종료와 보관 기간](docs/pickup-lifecycle.md): 차량 정보 중단·도착 후 30분, 최대 12시간, 종료 후 7일 정리와 기존 DB 적용 방법.
