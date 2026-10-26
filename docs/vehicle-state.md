# 차량 정보 끊김 표시용 응답

기능: 3.4 끊김 표시. 탑승자 웹이 오래된 차량 정보를 흐리게 보여주고 ETA 카운트다운을 이어가도록 응답을 바꿨다.

## 응답 형식

`GET /api/v1/pickups/{id}/vehicle-state`, `PUT` 같은 경로의 응답과 `GET /api/v1/ride-requests/{id}/tracking`의 `vehicle`에 같은 형식을 쓴다.

| 필드 | 설명 |
| --- | --- |
| `freshness` | `FRESH`: 30초 이내 정보 · `STALE`: 30초 초과 · `WAITING`: 정보 없음 또는 SDK 해제로 삭제 |
| `latitude`·`longitude`·`durationSeconds` | `FRESH`와 2분 이내 `STALE`에서만 내려준다. 2분이 지나면 null |
| `ageMillis` | 서버 시각 기준 정보의 경과 시간(ms). SDK 시각과 서버 수신 시각 중 이른 쪽을 기준으로 한다. `WAITING`이면 null |
| `validForMillis` | `FRESH`로 남은 시간(ms). `STALE`·`WAITING`이면 0 |
| `sourceTime`·`receivedAt`·`sequence` | 기존과 같다 |

`durationSeconds`는 `ageMillis`만큼 지난 시점의 값이다. 웹은 `durationSeconds - ageMillis`에서 시작해 카운트다운한다. 휴대전화 시계는 사용하지 않고 왕복 시간만큼 더 지난 것으로 본다.

## 정책값

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `majung.vehicle-freshness` | `PT30S` | `FRESH` 유지 시간 |
| `majung.vehicle-last-known` | `PT2M` | 마지막 위치·ETA를 내려주는 최대 시간. `vehicle-freshness`보다 짧으면 서버가 시작하지 않는다 |

30초·2분은 [SDK 콜백 간격 측정](https://github.com/majung-team/majung-vehicle-app/blob/main/docs/sdk-callback-timing.md) 뒤 정한 시작값이다. 실차 지연 상한으로 검증한 값은 아니다.

## 바뀌지 않은 것

- SDK 연결 해제(`DELETE`)는 기존처럼 좌표·ETA를 지우고 `WAITING`을 반환한다. 단순 통신 지연과 구분한다.
- 종료·취소·만료된 픽업은 차량 정보를 조회할 수 없다.
- 대기 중인 탑승자에게 보여주는 현재 픽업 남은 시간(`currentPickupRemainingSeconds`)은 `FRESH`일 때만 내려준다.
- 도착 표시는 `ARRIVED` 상태로만 한다. 카운트다운이 0이 돼도 도착으로 바꾸지 않는다.

## 확인 범위

`PickupApiTest`에서 30초 경과 시 `STALE`과 마지막 값, 121초 경과 시 좌표·ETA null과 `ageMillis`를 확인했다. 실제 통신 단절·실차는 확인하지 않았다.
