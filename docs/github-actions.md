# GitHub Actions 배포

## 실행 흐름

PR에서는 테스트·빌드를 실행한다. main push에서도 같은 검사를 거치며, 검사에 사용한 빌드 파일을 업로드해 배포 단계가 그대로 내려받는다. Actions는 커밋 SHA로 고정해 사용한다.

- 서버: JDK 25에서 `./gradlew test bootJar`, 배포 파일 검사·복구 시험
- 웹: Node.js 24에서 `npm ci`, `npm test`, JavaScript 구문 검사
- 자동 배포: main + `MAJUNG_DEPLOY_ENABLED=true`일 때 실행
- 수동 배포: 워크플로를 main에서 직접 실행하면 자동 배포 설정과 무관하게 검사 후 배포

서버는 첫 수동 배포를 확인한 뒤 자동 배포를 켰다. 웹의 검증 결과와 설정은 웹 저장소의 배포 문서에 기록한다. 실패한 deploy 작업만 재실행하면 통과한 test 작업은 반복하지 않고 같은 빌드 파일을 사용한다. 워크플로 전체를 수동 실행하면 테스트부터 다시 수행한다.

## 인증과 대상

장기 GCP 서비스 계정 키를 발급하지 않는다. GitHub OIDC를 Workload Identity Federation으로 교환한다. 조직·저장소 숫자 ID, main, push 또는 workflow_dispatch 조건을 함께 제한한다. 서버·웹 배포 계정을 구분하고 SSH 권한은 마중 인스턴스에만 준다. 애플리케이션 DB 비밀번호·카카오 키·관리자 키는 서버 환경 파일에 남는다.

GitHub 저장소 Secrets에 아래 **배포 설정**을 등록한다. 실제 값은 문서·코드에 쓰지 않는다.

| 이름 | 용도 |
| --- | --- |
| GCP_WORKLOAD_IDENTITY_PROVIDER | 마중 전용 OIDC 공급자 |
| GCP_DEPLOY_SERVICE_ACCOUNT | 해당 저장소의 배포 계정 |
| GCP_PROJECT_ID, GCP_ZONE | 대상 프로젝트와 영역 |
| GCP_INSTANCE, GCP_INSTANCE_ID | 대상 이름과 변경되지 않는 인스턴스 ID |
| GCP_SSH_USER | 배포 계정의 OS Login 사용자 |
| GCP_SSH_HOST_KEY | 운영자가 서버에서 직접 확인한 SSH 공개 호스트 키 |

`deploy/workflow.sh`는 대상 이름이 마중용인지, 실제 인스턴스 ID가 일치하는지 확인한다. IAP로 접속하며 고정한 호스트 키를 검증한다. 바이너리는 stdin으로 전송하고 SHA256과 커밋 SHA를 서버에서 다시 확인한다.

배포 계정에는 마중 인스턴스의 `roles/compute.osLogin`과 `compute.instances.getGuestAttributes` 조회 권한이 필요하다. gcloud의 프로젝트 조회에는 `compute.projects.get`을 별도로 허용한다. IAP 접근은 같은 인스턴스의 SSH 포트로 제한한다. 관리자 역할이나 다른 서비스 인스턴스 권한은 부여하지 않는다.

## 서버에 한 번 설치하는 파일

서버 저장소의 `deploy/release.py`를 운영자가 검토한 뒤 root 소유 `/usr/local/sbin/majung-deploy`로 설치한다. CI는 설치된 이 파일만 호출하고 설치 파일·비공개 정책을 덮어쓰지 않는다.

`/etc/majung/deploy-policy.json`은 root 소유·600으로 두고 `instance_id`와 `allowed_users`를 설정한다. `allowed_users`는 OS Login 사용자별 허용 대상을 배열로 지정한다. 서버 배포 계정은 backend만, 웹 배포 계정은 web만 허용한다. 이 값은 서버에만 둔다. sudo 권한도 이 명령으로 제한하며 OS Login 관리자 역할을 주지 않는다.

서버·웹 배포는 같은 잠금을 사용해 겹치지 않게 한다. 웹 압축 파일은 지정된 HTML·CSS·JS 일반 파일만 허용한다. 링크·상위 경로·중복 파일·환경 파일은 거부한다. 서버 JAR은 내장 커밋 SHA와 실행 형식을 확인한다.

## 상태 확인과 복구

서버는 `/api/v1/health`에서 DB 연결과 새 빌드의 커밋 SHA를 확인한다. 웹은 새로 생성한 `/release.json`으로 교체 여부를 확인한다. 새 버전의 상태 확인이 실패하면 이전 파일을 복원한다. 서버는 복원 뒤 다시 시작하고 기본 API 응답을 확인한다. 구성요소마다 직전 파일 한 개를 서버에 보관한다.

이 방식은 재시작하는 배포다. 짧은 중단이 있고, 메모리 로그인 세션은 재로그인이 필요할 수 있다. 블루·그린이나 무중단 배포로 기록하지 않는다.

DB 마이그레이션은 자동 실행하지 않는다. 스키마가 바뀌는 배포는 백업과 업그레이드 SQL·권한을 먼저 확인한다. 특히 카카오 계정과 차량 등록을 모두 반영하는 초기 배포에는 두 기능의 테이블이 필요하다. 코드 복구가 DB 변경까지 되돌리는 것은 아니다.

## 확인한 범위

2026년 10월 25일 [서버 수동 실행](https://github.com/majung-team/majung-backend/actions/runs/40154730036)에서 테스트·빌드, OIDC 인증, IAP SSH 전송, JAR 교체와 상태 확인을 통과했다. 첫 실행은 `compute.instances.getGuestAttributes` 권한 부족으로 실패했다. 해당 조회 권한을 마중 인스턴스에 추가한 뒤 deploy 작업만 재실행해 성공했다.

공개 HTTPS에서 `/api/v1/health`의 `UP`과 실행 커밋 SHA 일치를 확인했다. 미등록 차량 요청은 401, 외부 관리자 경로는 404로 차단됐다. 이 첫 시험 당시 카카오 로그인은 꺼져 있었다. 이후 로그인은 운영에 반영됐다. 현재 상태는 [운영 현황](https://github.com/majung-team/majung/blob/main/docs/runtime-2026-10-28.md)을 본다.

로컬에서는 서버 테스트·빌드, actionlint, 셸 구문 검사와 아카이브·체크섬·버전·실패 복구 시험을 실행했다. 실제 배포 중 장애를 발생시킨 복구 시험이나 실차 시험까지 통과한 것은 아니다. 자동 배포가 꺼져 있을 때 CD가 skipped인 것은 배포 성공을 의미하지 않는다.

공식 문서: [GitHub용 GCP 인증 Action](https://github.com/google-github-actions/auth), [배포 파이프라인의 Workload Identity Federation](https://cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines).
