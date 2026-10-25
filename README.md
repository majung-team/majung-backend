# majung-backend

마중 서버 API 저장소다.

기존 majung 저장소의 server/ 커밋 이력을 추출했다. 기존 main의 동작을 옮긴 것으로, 새 기능명세의 모든 기능을 완료한 상태는 아니다.

- 기획·진행 안내: https://github.com/majung-team/majung
- 기준 명세: https://app.notion.com/p/4026da9ae26a805abd45c0ee5885ef2f

## 실행

JDK 25로 `./gradlew test bootJar`를 실행한다. `./gradlew bootRun`으로 API를 127.0.0.1:8080에서 연다.
웹은 majung-web에서 따로 실행한다. 서버 JAR는 인접 웹 디렉터리를 읽지 않는다.
환경변수·deploy 프로필·스키마는 deploy/와 src/main/resources/를 확인한다.

## 협업

`npm ci`로 커밋 검사 훅을 설치한다. 성모·승준 모두 기능별로 서버·웹·차량 변경을 함께 맡는다.
각 저장소의 PR에서 관련 저장소 PR과 기능 번호를 연결한다. 다른 저장소의 준비가 필요하면 먼저 API 요청·응답을 합의한다.

[개정 명세 대조와 작업 순서](docs/revised-plan.md)를 확인한다. 아직 구현하지 않은 기능과 현재 기준 동작을 구분해 적었다.

[카카오 가입·닉네임 검색·연결 API](docs/account-connections.md): 서버 환경변수, 세션·CSRF, DB 업그레이드와 검증 범위.

## GitHub Actions

[테스트·배포·복구 흐름](docs/github-actions.md)을 참고한다. main의 검사 결과물을 마중 서버로 배포하며, 실제 Actions 검증 전에는 자동 배포를 꺼둔다. 앱 인증정보는 서버에만 보관한다.
