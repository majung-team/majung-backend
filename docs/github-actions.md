
# GitHub Actions CI/CD 및 운영 배포

마중은 GitHub Actions를 이용해 코드 검사, 빌드, 배포 파일 검증 및 운영 서버 배포를 자동화한다.

배포 인증에는 GitHub OIDC와 Google Cloud Workload Identity Federation을 사용한다. 장기 서비스 계정 키를 발급하지 않으며, 배포 대상과 실행 권한을 마중 운영 인스턴스로 제한한다.

백엔드는 단일 GCP VM 안에서 블루·그린 배포를 사용하고, 웹은 기존 정적 파일 교체 방식으로 배포한다.

이 문서는 CI/CD 실행 조건, 접근 권한, 배포 절차 및 검증 결과를 정리한 기록이다.

---

## 1. CI/CD 실행 조건

### 실행 기준

| 상황 | 동작 |
| --- | --- |
| Pull Request | 테스트·빌드 및 배포 파일 검사 |
| `main` push | 동일한 CI 검사 실행 |
| `main` push + 자동 배포 활성화 | CI 통과 후 운영 배포 |
| `main`에서 수동 워크플로 실행 | CI 검사 후 운영 배포 |
| CI 실패 | 배포 진행하지 않음 |

자동 배포는 다음 설정을 기준으로 실행한다.

```text
MAJUNG_DEPLOY_ENABLED=true
```

`main`에 푸시하더라도 자동 배포 설정이 활성화되어 있지 않으면 배포 작업은 실행되지 않는다.

수동 배포는 `workflow_dispatch`를 통해 `main`에서 실행하며, 자동 배포 설정과 관계없이 CI 검사 후 배포한다.

백엔드는 최초 수동 배포를 확인한 이후 자동 배포를 활성화했다.

### 저장소별 검사

| 항목 | 백엔드 | 웹 |
| --- | --- | --- |
| 실행 환경 | JDK 25 | Node.js 24 |
| 의존성 준비 | Gradle Wrapper | `npm ci` |
| 테스트 | `./gradlew test` | `npm test` |
| 빌드 | `./gradlew bootJar` | 웹 배포 파일 준비 |
| 추가 검사 | 배포 파일 및 복구 시험 | JavaScript 구문 검사 |

웹 저장소의 실제 CI 설정과 결과는 해당 저장소의 배포 문서에서 관리한다.

### 빌드 파일 재사용

CI에서 검증한 빌드 파일을 GitHub Actions 아티팩트로 업로드한다.

배포 작업은 이 아티팩트를 내려받아 사용하므로 테스트한 코드와 배포할 코드가 달라지는 상황을 줄인다.

외부 GitHub Actions는 커밋 SHA로 고정해 사용한다.

**실패한 deploy 작업만 재실행하는 경우**

기존 test 작업이 통과했다면 같은 빌드 아티팩트를 사용한다.

**워크플로 전체를 새로 실행하는 경우**

테스트 및 빌드 단계부터 다시 수행한다.

---

## 2. 전체 배포 흐름

```text
GitHub Pull Request / main push
              │
              ▼
       테스트 및 빌드
              │
              ▼
       배포 파일 검증
              │
              ▼
       빌드 아티팩트 저장
              │
              ▼
         배포 조건 확인
              │
              ▼
       GitHub OIDC 발급
              │
              ▼
  GCP Workload Identity Federation
              │
              ▼
      단기 GCP 인증 획득
              │
              ▼
         IAP SSH 접속
              │
              ▼
    운영 서버 배포 파일 전달
              │
              ▼
    SHA256·커밋 SHA 재검증
              │
              ▼
      후보 서버 실행 및 확인
              │
              ▼
         Caddy 연결 전환
              │
              ▼
       공개 HTTPS 상태 확인
              │
              ▼
         기존 서버 정리
```

실제 배포 과정에서 실패하면 해당 단계에 맞는 복구 절차를 수행한다.

CI 검사를 통과했다는 사실만으로 운영 배포까지 성공했다고 판단하지 않는다.

---

## 3. GCP 인증 및 접근 권한

### Workload Identity Federation

GitHub Actions는 OIDC 토큰을 발급받아 GCP Workload Identity Federation을 통해 단기 인증을 수행한다.

서비스 계정의 장기 JSON 키를 GitHub Secrets에 저장하거나 배포 서버로 전달하지 않는다.

인증 조건은 다음 정보를 기준으로 제한한다.

- GitHub 조직의 숫자 ID
- GitHub 저장소의 숫자 ID
- `main` 브랜치
- `push` 또는 `workflow_dispatch` 이벤트
- 지정된 배포 서비스 계정

저장소 이름만 일치하는 임의의 워크플로가 인증할 수 있도록 허용하지 않는다.

### 배포 계정 분리

백엔드와 웹은 서로 다른 배포 서비스 계정을 사용한다.

| 대상 | 허용 범위 |
| --- | --- |
| 백엔드 배포 계정 | 백엔드 배포 |
| 웹 배포 계정 | 웹 정적 파일 배포 |

배포 권한은 마중 운영 인스턴스에 한정한다.

다른 서비스 인스턴스의 변경 권한이나 OS Login 관리자 역할은 부여하지 않는다.

---

## 4. GitHub Actions 배포 설정

배포에 필요한 설정은 GitHub 저장소 Secrets에서 관리한다.

| 설정 이름 | 용도 |
| --- | --- |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | 마중 전용 OIDC 공급자 |
| `GCP_DEPLOY_SERVICE_ACCOUNT` | 저장소별 배포 서비스 계정 |
| `GCP_PROJECT_ID` | 대상 GCP 프로젝트 |
| `GCP_ZONE` | 운영 인스턴스 영역 |
| `GCP_INSTANCE` | 운영 인스턴스 이름 |
| `GCP_INSTANCE_ID` | 변경되지 않는 인스턴스 ID 검증 |
| `GCP_SSH_USER` | OS Login 사용자 |
| `GCP_SSH_HOST_KEY` | 운영자가 확인한 SSH 공개 호스트 키 |

실제 설정값은 공개 문서와 소스 코드에 포함하지 않는다.

### 애플리케이션 비밀 정보

다음 정보는 GitHub Actions 배포 설정과 별도로 운영 서버에서 관리한다.

- 데이터베이스 비밀번호
- 카카오 OAuth2 비밀 값
- 관리자 인증 정보
- 애플리케이션별 비밀 키

배포 파이프라인이 운영 비밀 값을 불필요하게 전달하거나 출력하지 않도록 한다.

---

## 5. GCP 인스턴스 접근

### IAP SSH

배포 작업은 Identity-Aware Proxy(IAP)를 통해 운영 인스턴스의 SSH 포트에 접속한다.

`deploy/workflow.sh`에서 다음 정보를 검증한다.

1. 배포 대상 이름이 마중 운영 인스턴스인지 확인
2. GCP에서 조회한 실제 인스턴스 ID 비교
3. IAP를 통한 SSH 연결
4. 사전에 고정한 SSH 호스트 키 확인
5. 배포 파일 전송

인스턴스 이름만으로 배포 대상을 신뢰하지 않는다.

### 필요한 권한

배포 계정에는 필요한 범위의 권한만 부여한다.

- `roles/compute.osLogin`
- `compute.instances.getGuestAttributes`
- `compute.projects.get`
- 대상 인스턴스의 SSH 포트로 제한된 IAP 접근 권한

각 권한은 실제 배포 동작에 필요한 범위로 한정한다.

관리자 역할이나 다른 인스턴스의 관리 권한을 일반 배포 계정에 부여하지 않는다.

---

## 6. 배포 파일 전송 및 검증

### 파일 전송

빌드 아티팩트는 SSH 연결의 표준 입력(stdin)을 통해 운영 서버에 전달한다.

배포 대상 서버에서 파일의 무결성과 소스 버전을 다시 확인한다.

### 백엔드 JAR

검증 항목:

- SHA-256 체크섬
- 빌드 기준 커밋 SHA
- JAR 실행 형식
- 배포 대상 및 허용된 파일 구성

CI에서 검사한 JAR와 운영 서버에서 설치할 JAR가 일치하는지 확인한다.

### 웹 배포 파일

웹 아카이브는 지정된 HTML·CSS·JavaScript 일반 파일만 허용한다.

다음 항목은 거부한다.

- 심볼릭 링크
- 상위 디렉터리 접근 경로
- 중복 파일
- 환경 설정 파일
- 허용되지 않은 파일 형식

웹 배포 파일이 운영 서버의 임의 경로나 비공개 설정을 덮어쓰지 않도록 제한한다.

---

## 7. 운영 서버 최초 설정

운영 서버에서는 배포 실행 파일과 비공개 정책을 별도로 설치한다.

이 과정은 최초 운영 환경 구성 시 관리자가 수행한다.

### 배포 실행 파일

저장소의 다음 파일을 운영자가 검토한다.

```text
deploy/release.py
```

검토 후 root 소유의 다음 위치에 설치한다.

```text
/usr/local/sbin/majung-deploy
```

GitHub Actions는 운영 서버에 설치된 실행 파일을 호출한다.

CI가 해당 실행 파일이나 서버의 비공개 배포 정책을 임의로 덮어쓰지 않는다.

### 배포 정책 파일

```text
/etc/majung/deploy-policy.json
```

소유자 및 권한:

- 소유자: root
- 파일 권한: `600`

정책 파일에는 다음 설정을 포함한다.

- `instance_id`
- `allowed_users`

`allowed_users`는 OS Login 사용자별 허용 배포 대상을 배열로 지정한다.

백엔드 배포 계정은 `backend`, 웹 배포 계정은 `web`만 배포할 수 있도록 제한한다.

### sudo 권한

배포 계정의 sudo 실행 권한은 설치된 배포 명령으로 제한한다.

배포 계정에 광범위한 관리자 권한을 부여하지 않는다.

### 동시 배포 방지

서버와 웹 배포는 같은 잠금을 사용한다.

이를 통해 서로 다른 워크플로가 동시에 운영 배포 작업을 실행하여 상태 파일이나 Caddy 설정을 충돌시키는 상황을 방지한다.

---

## 8. 백엔드 Blue-Green 배포

**운영 적용: 2026년 10월 29일**

서버 변경 [#45](https://github.com/majung-team/majung-backend/pull/45)부터 운영 환경에 블루·그린 배포를 적용했다.

같은 GCP VM에서 두 개의 애플리케이션 슬롯을 번갈아 사용한다.

| 슬롯 | 내부 포트 |
| --- | --- |
| Blue | `8081` |
| Green | `8082` |

외부 주소는 계속 `https://majung.cloud`를 사용한다.

독립된 VM 두 대를 구성한 방식은 아니다.

### 배포 순서

1. 현재 요청을 처리하지 않는 슬롯에 새 JAR 설치
2. 후보 서버 실행
3. 데이터베이스 연결 확인
4. JDBC 공유 세션 테이블 확인
5. 후보 서버의 커밋 SHA와 슬롯 확인
6. Caddy API 연결 대상을 후보 서버로 변경
7. 공개 HTTPS에서 새 버전 확인
8. 활성 정기 작업 슬롯 갱신
9. 전환 후 20초 동안 기존 요청 처리 대기
10. 이전 서버 종료

후보 서버 기동 또는 공개 상태 확인에 실패하면 기존 연결 대상으로 복구한다.

### 공유 로그인 세션

Spring Session JDBC를 사용하여 로그인 세션을 PostgreSQL에서 공유한다.

관련 테이블:

- `SPRING_SESSION`
- `SPRING_SESSION_ATTRIBUTES`

Blue와 Green이 같은 로그인 세션 및 CSRF 정보를 사용할 수 있도록 구성했다.

메모리 기반 세션에서 JDBC 세션으로 최초 전환할 때는 기존 사용자에게 한 번의 재로그인이 필요할 수 있다.

### 정기 작업

픽업 만료, 데이터 보관 정리 및 차량 연결 정보 정리처럼 중복 실행에 주의해야 하는 작업은 활성 슬롯에서만 수행한다.

후보 서버가 배포 검증 중에 동일한 정기 작업을 실행하지 않도록 한다.

### 배포 복구의 범위

블루·그린 복구는 애플리케이션 실행 슬롯과 Caddy 연결을 되돌리는 방식이다.

다음 항목까지 보장하지 않는다.

- VM 장애에 대한 이중화
- 데이터베이스 장애에 대한 이중화
- 모든 요청의 무중단 처리
- 기존 SSE 연결의 영구 유지
- DB 스키마 자동 롤백

전환 과정에서 SSE가 끊기면 웹은 재연결한 후 서버의 현재 상태를 다시 조회해야 한다.

[블루·그린 배포 및 복구 상세 절차](blue-green.md)

---

## 9. 웹 배포

웹은 정적 파일 교체 및 실패 복구 방식을 사용한다.

배포 후 생성한 `release.json`을 통해 실제 제공되는 웹 버전을 확인한다.

```text
https://majung.cloud/release.json
```

웹 배포는 백엔드의 블루·그린 슬롯 전환과 별개의 절차다.

구체적인 테스트와 배포 설정은 [majung-web](https://github.com/majung-team/majung-web)의 문서를 참고한다.

---

## 10. 배포 후 상태 확인

### 백엔드

공개 HTTPS 상태 확인:

```sh
curl -fsS https://majung.cloud/api/v1/health
```

확인 항목:

- `status`
- `revision`
- `slot`
- DB 연결
- 공유 세션 테이블 사용 가능 여부

실제 배포된 버전과 상태 확인 응답의 커밋 SHA가 일치하는지 확인한다.

블루·그린 배포 시에는 예상 슬롯과 실제 응답 슬롯도 함께 확인한다.

### 웹

```sh
curl -fsS https://majung.cloud/release.json
```

새로 배포한 웹 커밋과 `release.json`의 정보가 일치하는지 확인한다.

### 상태 확인 시 주의사항

기존 서버가 정상 응답을 반환하는 것만으로 후보 서버 배포가 성공했다고 판단하지 않는다.

반드시 예상한 **새 커밋 SHA와 슬롯**이 공개 주소에서 확인되는지 검사한다.

---

## 11. 데이터베이스 마이그레이션

DB 마이그레이션은 일반 CI/CD 배포 과정에서 자동 실행하지 않는다.

스키마 변경이 포함된 배포는 운영자가 별도로 준비한다.

### 사전 확인

1. 운영 DB 백업
2. 변경 SQL 검토
3. 기존 서버와 신규 서버의 스키마 호환성 확인
4. 필요한 테이블 및 컬럼 추가
5. DB 사용자 권한 확인
6. 신규 서버 배포

초기 카카오 계정 및 차량 등록 기능을 함께 배포하는 경우에는 두 기능에 필요한 데이터베이스 테이블이 준비되어 있어야 한다.

### 롤백 제한

애플리케이션을 이전 버전으로 되돌려도 이미 적용된 DB 스키마 변경은 자동으로 되돌아가지 않는다.

특히 이전 코드에서 사용할 수 없는 컬럼 삭제나 이름 변경은 블루·그린 방식만으로 안전하게 복구할 수 없다.

이전 버전과 신규 버전의 동시 실행을 고려해 호환되는 스키마를 먼저 적용해야 한다.

---

## 12. 최초 수동 배포 기록

**검증일: 2026년 10월 25일**

[GitHub Actions 실행 기록](https://github.com/majung-team/majung-backend/actions/runs/40154730036)

### 최초 실행 문제

초기 배포에서는 GCP의 다음 권한이 부족해 배포 작업이 실패했다.

```text
compute.instances.getGuestAttributes
```

배포 대상 인스턴스에 필요한 조회 권한을 추가했다.

이후 실패한 `deploy` 작업만 재실행하여 기존 CI 빌드 아티팩트로 배포했다.

### 확인 결과

- 서버 테스트 및 JAR 빌드 통과
- GitHub OIDC 인증 성공
- GCP Workload Identity Federation 인증 성공
- IAP SSH 접속 및 파일 전송 성공
- 운영 JAR 교체
- 공개 HTTPS 상태 확인
- 배포 커밋 SHA 일치

### 접근 차단 확인

공개 서버에서 다음 응답을 확인했다.

| 요청 | 결과 |
| --- | --- |
| `/api/v1/health` | `UP` |
| 미등록 차량 요청 | HTTP 401 |
| 외부 관리자 경로 | HTTP 404 |

이 기록은 2026년 10월 25일 최초 배포 시험 결과다.

당시 카카오 로그인은 비활성화되어 있었고 이후 운영 환경에 반영됐다.

현재 로그인 및 운영 상태는 [10월 29일 운영 기록](https://github.com/majung-team/majung/blob/main/docs/runtime-2026-10-29.md)을 참고한다.

---

## 13. Blue-Green 전환 검증

**검증일: 2026년 10월 29일**

운영 서버에서 공유 세션과 블루·그린 최초 전환을 확인했다.

주요 검증 항목:

- 운영 DB 백업
- JDBC 세션 테이블 및 권한 준비
- `standalone`에서 `blue` 슬롯으로 전환
- 공개 서버 커밋 SHA 및 슬롯 확인
- 기존 로그인 세션과 CSRF 처리
- 전환 중 공개 health 응답 확인

전환 구간에서 공개 health 조회 70회 모두 HTTP 200을 반환했다.

또한 전환 전 발급한 익명 세션과 CSRF 정보를 이용해 전환 후 로그아웃 요청을 처리했다.

이 결과는 해당 전환 시험 구간의 관측 결과이며, 모든 장애 상황에서 무중단을 보장한다는 의미는 아니다.

후보 서버 기동 실패, 공개 확인 실패, Caddy reload 실패 복구는 자동화 테스트로 검증했다.

운영 환경에서 각 장애를 인위적으로 발생시킨 시험은 수행하지 않았다.

---

## 14. 로컬 검증 범위

배포 자동화 변경 시 로컬에서 다음 항목을 검사했다.

- 서버 테스트 및 `bootJar` 빌드
- `actionlint`
- 셸 스크립트 구문 검사
- 배포 아카이브 검증
- SHA-256 체크섬 검사
- 커밋 SHA 대조
- 예상 버전 확인
- 배포 실패 복구 테스트

### 검증 결과 해석

자동화 테스트에서 복구 절차가 통과했다는 사실과 실제 운영 장애 상황에서 복구에 성공했다는 사실은 구분한다.

또한 자동 배포 설정이 꺼진 상태에서 CD 작업이 `skipped`로 표시된 경우, 이는 배포가 실행되지 않았다는 뜻이다.

`skipped`를 배포 성공으로 기록하지 않는다.

---

## 15. 관련 문서

- [백엔드 README](../README.md)
- [블루·그린 배포](blue-green.md)
- [픽업 자동 만료 및 보관 정책](pickup-lifecycle.md)
- [차량 등록 및 권한 관리](vehicle-registration.md)
- [차량 계정 연결](vehicle-account-link.md)
- [전체 시스템 아키텍처](https://github.com/majung-team/majung/blob/main/docs/architecture.md)
- [운영 버전 및 배포 기록](https://github.com/majung-team/majung/blob/main/docs/runtime-2026-10-29.md)
- [개발 현황 및 PR 근거](https://github.com/majung-team/majung/blob/main/docs/development-status.md)

### 공식 참고 자료

- [Google Cloud 인증 GitHub Action](https://github.com/google-github-actions/auth)
- [Workload Identity Federation과 배포 파이프라인](https://cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines)

---

## 문서 관리 기준

이 문서는 마중의 GitHub Actions 기반 CI/CD와 운영 배포에 적용한 인증·검증·복구 절차를 설명한다.

최초 수동 배포와 블루·그린 전환의 실제 기록은 서로 구분하여 보존한다.

배포 정책을 변경하는 경우 GitHub Actions, GCP 권한, 운영 서버의 설치 파일 및 공개 상태 확인 조건을 함께 점검한다.

자동화 테스트, 실제 운영 배포, 장애 복구 및 실차 검증은 각각 별도의 결과로 관리한다.
