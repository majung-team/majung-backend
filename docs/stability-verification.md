# 복구·ETA·권한 검증

2026-10-31 기준. 경유지 좌표 복구와 함께 확인한 범위다. 실제 차량 시동 종료와 휴대전화 푸시 수신을 자동 테스트 통과로 표시하지 않는다.

## ETA의 기준

`durationSeconds`는 차량 SDK가 보낸 원본 남은 시간이다. 서버는 이 값을 깎지 않고 `ageMillis`와 `validForMillis`를 따로 반환한다. `ageMillis`는 SDK 측정 시각과 서버 수신 시각 중 더 오래된 시각부터 계산한다. 웹은 여기에 HTTP 왕복 시간과 응답 후 경과 시간을 더해 한 번만 차감한다.

예를 들어 원본 180초, 서버 경과 시간 20초, HTTP 왕복 0.25초, 응답 후 0.75초면 웹은 159초로 계산한다. 같은 20초를 두 번 빼지 않는다. 새 SDK ETA가 600초나 120초로 바뀌면 이전 카운트다운 대신 새 값을 기준으로 계산한다. 서버가 정체 시간을 따로 추정하지는 않는다.

`PickupApiTest.exportsRawEtaAndFreshnessResponsesForDeployedWebCompatibility`가 실제 HTTP 응답을 `build/contracts/eta-api-responses.json`에 만든다. `deploy/test_eta_contract.mjs`는 그 응답을 웹의 실제 `model.js`에 넣어 검증한다.

```sh
./gradlew test bootJar
node deploy/test_eta_contract.mjs ../majung-web/model.js
```

서버 CI는 현재 배포된 웹 리비전 `3cb66cbe3e898ffeac04d5309a7f5bf01689baf6`을 별도로 checkout해 같은 시험을 실행한다. 웹 계산 방식이 바뀌면 새 웹을 확인하고 이 기준 리비전도 갱신한다. 이 시험이 모든 신·구 API의 호환성을 보장하는 것은 아니다.

확인하는 조건은 다음과 같다.

- 원본 ETA·서버 경과 시간·HTTP 지연이 중복 차감되지 않음
- 30초 경계에서 STALE, 2분 이상 지난 카운트다운 숨김
- 정체로 늘어난 ETA와 짧아진 ETA를 새 값으로 반영
- 오프라인·완료·취소·만료 상태에서 위치와 ETA 숨김
- 30분간 차량 갱신이 없던 픽업은 만료 처리, 뒤늦은 차량 전송 거절

## 기존 시험과 역할

| 항목 | 확인하는 시험 | 범위 |
| --- | --- | --- |
| 동시 참여·준비 상태·취소와 위치 업로드 경쟁 | PickupApiTest | 실제 로컬 HTTP·DB 동시 요청 |
| 제안 교체·중복 확정·이전 장소 전송 거절·도착 시 미확정 제안 정리 | PlaceProposalTest | 서버 확정 상태 유지·버전 검증·도착/종료 정리 |
| 세션 로그아웃·다른 계정·철회 후 SSE 차단 | PickupEventsAuthorizationTest, SharedSessionServerTest | SSE와 공유 JDBC 로그인 세션 |
| 차량 계정 해제·차량 철회·카카오 해제 시 활성 픽업·조회·푸시 즉시 차단 | RideRequestTest, VehicleAccountTest | 연결 해제 후 픽업 취소·추적 차단·구독 제거 |
| 구독 해제·픽업 종료·잘못된 접근 | ArrivalPushTest | 구독 상태와 모의 푸시 전송 |
| 앱 저장 정보 복원·일시 503·잘못된 권한 삭제 | 차량 앱 PickupRecoveryTest | Android Keystore·로컬 HTTP 시험 서버 |
| 새 프로세스와 SDK 연결에서 이전 픽업 이어가기 | 차량 앱 기존 프로세스 복구 기록 | 에뮬레이터. 실제 시동 시험과 구분 |
| 배포 후보 실패·공개 health 실패·이전 슬롯 복귀 | deploy/test_blue_green.py | 배포 명령 모의 시험 |

이름이 비슷해도 웹 푸시 수신 시험과 서버 전송 시험은 다르다. Android 실기기·iPhone 홈 화면 앱의 실제 푸시 수신, 실제 차량 시동 종료·재시동은 미확인으로 남긴다.

## 이미 적용한 운영 점검

[백업 복원과 운영 점검](operations-recovery.md)에 실제 복원 결과와 알림 적용 범위를 기록했다. API·DB 연결, 백업·복원 지연, 모든 ACTIVE 차량 정보의 지속 지연, 만료 처리 지연을 점검한다. 외부 HTTPS 측정과 이메일 알림 정책이 활성화돼 있다.

API 5xx 비율, 응답 시간 히스토그램 p95 상한, DB 풀 대기, OAuth 콜백 결과는 내부 관리 포트에서 수집하고 매분 운영 점검에 반영한다(서버 PR #58·#59). 매일 비공개 Cloud Storage로 백업을 복사하고 다운로드 해시 검증 후 성공 시각을 남긴다. 30시간 동안 성공 기록이 없으면 운영 경보로 연결된다(서버 PR #53·#56·#59).

10/31 외부 복사본을 임시 PostgreSQL에 복원해 핵심 9개 테이블, 서버 기동, JDBC 세션 인증, 계정·픽업 HTTP 조회, 로그아웃 후 차단을 모두 확인했다(25.68초). 시험 후 임시 DB는 0개였다. 이 인증 시험은 카카오 서버의 신규 인증 발급과 다르다.

기간별 배포 실패율 대시보드와 실제 경보 이메일 수신은 이번 확인에 포함하지 않는다. 배포 실패는 Actions 실행 기록에서 확인한다. 모든 차량 정보의 지속 지연을 감지하는 경보와 개별 차량의 STALE 비율 분석도 구분한다.

## DB 변경과 배포

현재 블루·그린 배포는 이전 슬롯으로 트래픽을 되돌릴 수 있지만 DB 변경 자체를 되돌리지는 않는다. 구버전도 읽을 수 있는 테이블·컬럼을 먼저 추가하고 데이터를 옮긴 뒤, 구버전이 더는 필요 없을 때 삭제·제약 강화 작업을 별도 배포로 수행한다. 실패했다고 운영 DB를 이전 백업으로 곧바로 덮어쓰지 않는다.

## 2026-11-12 후속 보완 및 Wiki 회고

- **운영·배포 복구**: PR `#66`으로 `/etc/majung/active-slot` 읽기 권한(`0710`)을 복구해 자동 만료 스케줄러를 정상화했고, PR `#75` (`3546902`)로 후보 서버 기동 확인 대기 한도를 `75초`로 늘렸으며, `#56`·`#62`로 백업 `gcloud ssh-keygen` 출력 분리·예약 WIF 인증 문제를 해결했다.
- **종료 정리·계정 연동**: PR `#64`로 도착·연결 해제 시 미확정 장소 제안과 도착 푸시 구독 정리를 보강했고, `7c93456` (`#78`)·`102a9a4`로 iOS 로그인 결과 전달, 카카오 장소 검색 프록시, 계정 SSE, 게스트 대기 이탈, 회원 탈퇴를 추가한 뒤 `84d9498`에서 임시 운영 콘솔을 정리했다.
- **최신 테스트 범위**: 백엔드 Gradle 테스트 총 125건 중 `124 passed, 1 skipped` 및 `deploy/` Python 테스트 `29개` 통과를 확인했다.
- **상세 회고**: [픽업이 끝나거나 연결을 끊으면 위치 공유는 바로 멈출까?](https://github.com/majung-team/majung/wiki/%ED%94%BD%EC%97%85%EC%9D%B4-%EB%81%9D%EB%82%98%EA%B1%B0%EB%82%98-%EC%97%B0%EA%B2%B0%EC%9D%84-%EB%81%8A%EC%9C%BC%EB%A9%B4-%EC%9C%84%EC%B9%98-%EA%B3%B5%EC%9C%A0%EB%8A%94-%EB%B0%94%EB%A1%9C-%EB%A9%88%EC%B6%9C%EA%B9%8C) · [단일 VM Blue-Green 배포와 예약 백업은 왜 중간에 멈췄을까?](https://github.com/majung-team/majung/wiki/%EB%8B%A8%EC%9D%BC-VM-Blue-Green-%EB%B0%B0%ED%8F%AC%EC%99%80-%EC%98%88%EC%95%BD-%EB%B0%B1%EC%97%85%EC%9D%80-%EC%99%9C-%EC%A4%91%EA%B0%84%EC%97%90-%EB%A9%88%EC%B7%84%EC%9D%84%EA%B9%8C)
