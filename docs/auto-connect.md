# 탑승자 자동 연결과 끊기

기능: 2.1 자동 연결, 2.2 탑승자 끊기. 차량 QR로 시작하는 초대에 적용한다. 마중 요청·직접 초대로 시작한 픽업은 요청 목록에서 관리하며 이 API로 끊을 수 없다.

## 자동 연결

`POST /api/v1/pickups/{id}/participants`

- `displayName`은 선택이다. 없거나 공백이면 `탑승자`로 저장한다. 12자를 넘으면 400이다.
- 초대 링크를 연 첫 사람이 바로 `ACTIVE`로 연결된다. 초대는 이 순간 소멸한다. 두 번째 요청은 410 `INVITE_UNAVAILABLE`이다.
- `WAITING_APPROVAL` 상태로 바뀌는 경로는 없다. 이전에 만들어져 이 상태로 남은 픽업과 이전 앱을 위해 값과 승인 API(`POST .../approval`)는 남겨 두었다. 이미 `ACTIVE`·`ARRIVED`인 픽업에 승인을 보내면 200으로 그대로 응답한다.

## 탑승자 끊기

`DELETE /api/v1/pickups/{id}/participant` (운전자 토큰)

| 응답 | 조건 |
| --- | --- |
| 200 `{inviteToken, expiresAt}` | 연결된 탑승자를 끊고 새 초대를 발급. 상태는 `INVITING` |
| 409 `NO_PARTICIPANT` | 연결된 탑승자가 없음 |
| 409 `RIDE_REQUEST_PICKUP` | 마중 요청·직접 초대로 시작한 픽업 |
| 403 · 401 | 운전자 토큰이 아니거나 없음 |
| 410 `PICKUP_ENDED` | 종료·만료된 픽업 |

- 끊긴 탑승자의 토큰은 바로 무효가 된다. 조회·준비 상태 변경은 403이다.
- 이전 탑승자의 준비 상태와 차량 위치·ETA는 지운다. 차량 정보 순번은 유지하므로 차량 앱은 이어서 증가하는 순번으로 보낸다.
- `INVITING` 동안 차량 정보 전송은 403 `APPROVAL_REQUIRED`다. 새 탑승자가 연결되면 다시 보낼 수 있다.
- 새 초대의 유효 시간은 기존 초대와 같다(`majung.invite-ttl`).

## 확인 범위

`PickupApiTest`, `RideRequestTest`, `DeploymentHttpTest`에서 자동 연결, 이름 생략, 두 번째 사용자 거절, 끊긴 탑승자의 접근 차단, 새 초대로 재연결, 마중 요청 픽업 보호, 종료 후 거절을 확인했다. 실제 차량·휴대전화 연결은 확인하지 않았다.
