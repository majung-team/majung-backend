# 서버 블루·그린 배포

API 서버 두 자리를 번갈아 사용한다. blue는 내부 8081, green은 내부 8082다.
외부에서는 계속 `https://majung.cloud`로 접속한다. 별도 인스턴스나 외부 포트를 추가하지 않는다.

## 배포 순서

1. 현재 요청을 받지 않는 서버에 새 JAR를 넣는다.
2. 해당 서버의 DB 연결, 세션 테이블, 커밋 SHA와 슬롯 이름을 확인한다.
3. Caddy의 API 연결 대상을 바꾸고 공개 HTTPS에서도 같은 버전·슬롯인지 확인한다.
4. 확인에 실패하면 Caddy와 정기 작업 담당을 기존 서버로 되돌린다.
5. 정상 전환 후 20초 동안 기존 요청을 기다린 뒤 이전 서버를 종료한다.

테스트와 배포는 기존 GitHub Actions를 사용한다. 수동 실행으로 같은 커밋을 다시 배포할 때도
슬롯 이름까지 검사하므로 이전 서버의 응답을 새 배포 성공으로 오인하지 않는다.
웹 정적 파일 배포 방식은 그대로다.

## 로그인과 정기 작업

로그인 세션은 PostgreSQL의 `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`에 저장한다.
두 서버가 같은 세션과 CSRF 정보를 사용하며 한쪽에서 로그아웃하면 다른 쪽에서도 해제된다.
메모리 세션에서 처음 옮기는 배포에는 기존 로그인 사용자의 재로그인이 한 번 필요하다.
픽업·차량 권한·탑승자 토큰은 별도 DB 데이터라 이 전환으로 삭제하지 않는다.

픽업 만료·보관 기간 정리·차량 연결 코드 정리·카카오 연결 해제 재시도는
`/etc/majung/active-slot`에 지정된 서버만 실행한다. 후보 서버는 점검 중 이 작업을 수행하지 않는다.
Spring Session 자체의 만료 세션 정리는 두 서버에서 실행할 수 있으며 DB 삭제 방식은 멱등이다.
이벤트 스트림은 Caddy 재설정이나 이전 서버 종료로 재접속할 수 있다. 클라이언트의 재접속과
상태 재조회가 필요하며, SSE 연결 자체가 영구 유지된다는 뜻은 아니다.

## 최초 설치 — 운영자 작업

이 단계는 CI가 수행하지 않는다. 마중 인스턴스인지 확인하고 root 권한으로 한 번 준비한다.

1. PostgreSQL 백업 후 `src/main/resources/db/upgrade-shared-sessions.sql`을 적용한다.
   기존 테이블은 지우지 않는다. 앱 DB 계정에 두 테이블의 SELECT·INSERT·UPDATE·DELETE를 허용한다.
2. 공유 세션을 사용하는 서버 버전을 기존 `majung.service`에 먼저 배포한다.
3. `majung-slot.sh`를 root 소유 `/usr/local/libexec/majung-slot`에 755로 설치한다.
   `majung@.service.example`은 `/etc/systemd/system/majung@.service`로 설치하고 daemon-reload한다.
4. root 소유 `/opt/majung/slots/blue`, `green`을 만들고 현재 검증된 JAR를 blue/server.jar에 복사한다.
   JAR는 root 소유 644, 디렉터리는 755로 둔다. 환경변수는 기존 root 전용 server.env를 사용한다.
5. 정기 작업 파일을 `standalone`으로 두고 blue를 시작한다. 내부 health의 버전과 슬롯을 확인한다.
6. Caddy의 API reverse_proxy 블록 전체를 `import /etc/caddy/majung-upstream.caddy`로 바꾼다.
   해당 파일은 8081 대상의 reverse_proxy 블록이며 기존 연결·응답 시간 제한을 유지한다.
   validate 후 reload하고 공개 health를 확인한다.
7. active-slot을 blue로 기록하고 `active-backend.json`에 active·revision을 저장한다.
   기존 majung.service를 종료하고 비활성화한다. blue 서비스는 재부팅 시 시작하도록 활성화한다.
8. root 소유 설치 프로그램을 현재 `deploy/release.py`로 교체한다.
   기존 deploy-policy.json의 다른 필드를 유지하며 `backend_mode`만 `blue-green`으로 설정한다.

시작 서비스는 전환할 때마다 새 슬롯을 enable, 이전 슬롯을 disable해야 한다.
설정·상태 파일은 root만 변경한다. 최초 전환 실패 시 백업한 Caddy와 정책을 복구하고
기존 majung.service를 다시 시작한 뒤 공개 health를 확인한다. DB 테이블은 되돌려 삭제하지 않는다.

## 운영 확인

`GET /api/v1/health`의 `revision`과 `slot`을 확인한다. root 상태 파일은
`/var/lib/majung-deploy/active-backend.json`, Caddy 연결 파일은
`/etc/caddy/majung-upstream.caddy`다. 파일끼리 내용이 다르면 배포를 중단한다.
슬롯 서비스 상태는 `systemctl status majung@blue majung@green`으로 확인한다.
환경변수·쿠키·DB 세션 내용은 로그나 GitHub에 붙이지 않는다.

DB 변경은 별도 검토한다. 이전 서버가 읽을 수 없는 열 삭제·이름 변경은 이 배포만으로
안전하게 되돌릴 수 없다. 먼저 호환되는 열 추가와 데이터 전환을 끝내야 한다.

## 확인한 범위

자동 테스트에서 후보 기동 실패, 공개 확인 실패, Caddy reload 실패의 복구와 같은 SHA 재배포 검사를 다룬다.
두 실제 HTTP 서버와 같은 시험 DB를 사용해 로그인·CSRF 공유 및 로그아웃 동기화를 검증한다.
10월 29일 운영 DB 백업·세션 테이블·권한 추가 후 `standalone → blue` 최초 전환을 확인했다.
전환 중 공개 health 70회 모두 200이었고, 전환 전 발급한 익명 세션·CSRF로 전환 후 로그아웃이 처리됐다.
이는 이 시험 구간의 결과이며 모든 장애에서 무중단을 보장하는 수치가 아니다.
후보 기동 실패·공개 확인 실패·reload 실패 복구는 자동 테스트로 검증했고 운영 장애를 강제로 발생시키지는 않았다.
이전 JVM이 종료 신호를 받고 143으로 끝나는 경우도 정상 종료로 처리한다.
서비스 예시에 `SuccessExitStatus=143`을 지정해 정상 배포 종료가 failed로 남지 않게 했다.
