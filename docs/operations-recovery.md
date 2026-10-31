# 백업 복원과 운영 점검

마중 VM에만 적용한다. 플리플리 서버와 알림 설정은 변경하지 않는다.

## 무엇을 확인하나

| 점검 | 기준 |
| --- | --- |
| API·DB 연결 | 활성 슬롯의 health가 UP인지 확인 |
| 백업 | 가장 최근 백업이 30시간 이내에 생성됐는지 확인 |
| 복원 시험 | 최근 8일 안에 실제 복원과 서버 기동 시험을 통과했는지 확인 |
| 차량 정보 | ACTIVE 픽업 전부의 위치·ETA 갱신이 끊긴 상태가 3분 이상 이어지는지 확인 |
| 만료 처리 | 만료 기한이 3분 이상 지난 픽업이 남아 있는지 확인 |
| 점검기 자체 | 결과 파일이 없거나 3분 이상 갱신되지 않으면 실패 처리 |

진행 중인 픽업이 없으면 차량 정보 지연으로 판단하지 않는다. 일부 차량의 연결 끊김은 사용자 화면의 최신성 안내로 처리한다. 모든 차량의 갱신이 함께 멈춘 경우에 운영 알림을 낸다.

API는 `/api/v1/health/operations`에서 UP 또는 503만 공개한다. 백업 경로·픽업 수·실패 이유는 VM 내부에만 보관한다. 이 API는 배포 성공 판정용 health와 별개다.

## 백업과 복원 시험

기존 `majung-backup.timer`는 매일 UTC 00:00(KST 09:00)에 PostgreSQL custom-format 백업을 만든다. 백업은 VM의 `/var/backups/majung`에 제한된 권한으로 보관하며 7일을 기준으로 정리한다.

`majung-restore-check.timer`는 일요일 UTC 03:15(KST 12:15)부터 최대 5분 안에 실행한다.

1. 가장 최근 백업을 임의 이름의 임시 DB에 복원한다.
2. 계정·차량·픽업·요청·연결·푸시·로그인 세션 테이블 9개의 존재와 조회 권한을 확인한다.
3. 현재 활성 슬롯의 JAR를 임시 DB에 연결해 Hibernate 스키마 검증과 health 응답을 확인한다.
4. 시험 서버를 종료하고 임시 DB를 삭제한 뒤 성공 기록을 남긴다.

시험 서버는 localhost:18099에서만 열고 카카오 로그인·푸시 발송·활성 슬롯 정기 작업은 끈다. 운영 DB를 복원 대상으로 사용하지 않는다. 이 시험은 백업 데이터와 서버 기동을 확인하는 절차이며, 실제 사용자 로그인이나 진행 중 픽업의 복구 시험을 대신하지 않는다.

```sh
sudo systemctl start majung-restore-check.service
sudo systemctl status majung-restore-check.service
sudo cat /var/lib/majung-operations/restore.json
```

실패하면 `/var/lib/majung-operations/restore-server.log`와 journal을 VM 안에서 확인한다. 백업 원본·로그·행 데이터는 GitHub에 올리지 않는다. 실패 기록은 복원 재시험에 성공해야 지워진다.

**남은 한계:** 백업이 같은 VM 디스크에 있으므로 VM 디스크 자체가 유실되면 보호하지 못한다. 별도 저장소 보관과 해당 사본의 복원은 아직 적용하지 않았다.

## 장애 확인 순서

`majung-operations.timer`가 1분마다 점검한다. 두 HTTPS 주소를 외부에서 확인하는 Cloud Monitoring 알림은 두 측정 위치 이상의 실패가 2분 지속될 때 동작하도록 설정한다.

```sh
sudo /usr/local/sbin/majung-operations check
sudo cat /var/lib/majung-operations/status.json
sudo systemctl list-timers 'majung-*'
```

- `API_OR_DATABASE_UNAVAILABLE` / `HOST_CHECK_FAILED`: 활성 슬롯과 DB 연결부터 확인한다.
- `BACKUP_OVERDUE`: 백업 서비스의 실행 결과와 디스크 여유 공간을 확인한다.
- `RESTORE_CHECK_OVERDUE` / `RESTORE_CHECK_FAILED`: 복원 시험 로그를 확인한 뒤 재시험한다.
- `ALL_ACTIVE_TELEMETRY_STALE`: 차량 앱·SDK·통신 상태를 확인한다.
- `EXPIRATION_DELAY`: 활성 슬롯의 정기 작업 실행 여부를 확인한다.

장애 알림을 받았다고 운영 서버나 DB부터 재시작하지 않는다. 실패한 항목을 확인하고 필요한 조치만 한다.

## 최초 설치·설정

`deploy/operations.py`를 root 소유 `/usr/local/sbin/majung-operations`에 0755로 설치하고, 같은 폴더의 systemd unit 4개를 `/etc/systemd/system`에 설치한다. 스크립트는 root와 마중 호스트 이름을 확인하며 임시 DB 이름 외의 DB 삭제를 허용하지 않는다. VM에서는 다른 서비스 계정 인증 정보를 추가로 저장하지 않는다.

최초 복원 시험과 점검에 성공한 뒤 두 timer를 활성화한다.

```sh
sudo systemctl daemon-reload
sudo systemctl start majung-restore-check.service
sudo /usr/local/sbin/majung-operations check
sudo systemctl enable --now majung-operations.timer majung-restore-check.timer
```

Cloud Monitoring 설정은 두 health 주소를 먼저 확인한 뒤 실행한다. 기존 다른 서비스의 정책은 변경하지 않는다.

```sh
python3 deploy/configure-monitoring.py --project YOUR_PROJECT --email YOUR_OPERATIONS_EMAIL
```

[Cloud Monitoring uptime check 안내](https://docs.cloud.google.com/monitoring/uptime-checks)와 [알림 정책 JSON 예시](https://docs.cloud.google.com/monitoring/alerts/policies-in-json)를 기준으로 구성했다. 정책 생성과 실제 이메일 수신 확인은 구분해서 기록한다.

## 2026-10-31 적용 기록

- 최신 일일 백업을 임시 DB에 복원하고 주요 테이블 9개 조회, 현재 운영 JAR의 스키마 검증·health 응답을 확인했다. 약 23초가 걸렸다.
- 종료 후 시험용 DB가 0개인지 확인했다. 운영 DB를 덮어쓰거나 운영 서비스를 재시작하지 않았다.
- 주간 복원 timer와 1분 상태 점검 timer를 활성화했다. 최초 점검에서 백업·복원·API·만료 처리 이상은 없었다. ACTIVE 픽업은 없어서 실제 차량 지연 경보는 유발하지 않았다.
- 로컬 Java 테스트 102개 중 101개 통과, 1개 제외. 배포·운영 Python 테스트 19개 통과. 제외된 시험을 통과에 포함하지 않았다.
- 공개 API health와 운영 점검 health 모두 HTTPS UP을 확인했다.
- 마중 전용 HTTPS 점검 2개와 이메일 알림 정책 1개를 Cloud Monitoring에 생성했다. 기존 플리플리 정책·채널은 수정하지 않았다.
- 실제 장애를 유발하지 않았으므로 장애 발생부터 이메일 도착까지는 미확인이다.
- systemd의 `UMask=0077`에서도 서버가 결과 파일을 읽도록 상태 폴더 권한을 0750으로 명시했다. 같은 umask를 적용한 재발 방지 테스트도 통과했다.

## VM 외부 백업

`Private database backup` Actions가 매일 09:30 KST에 실행된다. VM의 최근 PostgreSQL 백업을 SSH로 받아 마중 전용 비공개 Cloud Storage에 복사한다. 업로드한 세대를 다시 내려받아 SHA-256이 같은지 확인한다. 실패한 실행은 GitHub Actions에서 확인하며, 저장소 알림 설정에 따라 메일이 전달된다.

- 버킷: `gs://majung-private-backups-305499581905`, 서울 리전
- 공개 접근 차단 및 버킷 단위 IAM 적용. 백엔드 배포 계정에 이 버킷의 생성·조회만 허용
- 14일 경과 후 삭제 정책. Cloud Storage 기본 soft delete 기간은 별도로 적용
- 개인 키를 VM에 추가하지 않고 Actions의 단기 GCP 인증 사용
- 임시 파일은 작업 전용 폴더에 0600 권한으로 저장하고 작업 종료 시 제거
- 백업 파일은 GitHub 아티팩트·소스 저장소·로그에 올리지 않음
- 호스트 이름뿐 아니라 GCP 인스턴스 ID를 대조. 플리플리 인스턴스는 대상이 아님

수동 실행은 Actions → Private database backup → Run workflow에서 한다. 로컬 백업 생성과 복원 시험 타이머는 계속 별도로 동작한다. 외부 복사본의 해시 확인은 PostgreSQL 복원 시험을 대신하지 않는다.

### 복원 DB의 세션·픽업 기능 확인

복원 전용 CLI(`RestoreVerification`)는 `majung_restore_check_<임의 ID>` DB에서만 시작한다. 복원한 스키마에 합성 계정·로그인 세션·픽업을 추가하고 실제 HTTP 요청으로 다음을 확인한다.

1. JDBC 세션을 읽어 로그인 상태 확인
2. 같은 쿠키로 내 계정 조회
3. 픽업 소유자 토큰으로 픽업 조회
4. 로그아웃 후 세션 삭제 및 같은 쿠키의 계정 접근 차단

카카오 서버에서 새 인증을 발급받는 시험은 아니다. 외부 카카오·푸시는 비활성화하고, 시험 자료와 복원 데이터는 임시 DB 삭제 시 함께 제거한다. CLI는 웹 API로 노출되지 않으며 운영 DB 이름으로 실행하면 시작 전에 거부한다.
