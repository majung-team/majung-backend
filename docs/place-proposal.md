# 장소 제안

기능: 6.1 장소 제안. 탑승자와 운전자가 지도에서 고른 다른 장소를 제안한다. 이 API는 제안만 저장하며 확정 장소는 바꾸지 않는다. 경로 요청과 장소 확정(6.2)은 차량 앱의 승인 흐름이 맡는다.

## API

| 요청 | 권한 | 동작 |
| --- | --- | --- |
| `PUT /api/v1/pickups/{id}/place-proposal` `{place:{label,latitude,longitude}}` | 운전자 또는 연결된 탑승자 토큰 | 제안을 저장한다. 이미 있으면 새 제안으로 바꾼다 |
| `DELETE /api/v1/pickups/{id}/place-proposal` | 운전자 또는 연결된 탑승자 토큰 | 제안을 지운다. 없어도 200 |
| `PUT·DELETE /api/v1/ride-requests/{id}/place-proposal` | 로그인한 탑승자 | 마중 요청으로 시작한 픽업에서 같은 동작 |

응답과 `GET /api/v1/pickups/{id}`는 `placeProposal`을 포함한다. 제안이 없으면 `null`이다.

```json
{
  "place": { "label": "예시 장소", "latitude": 37.0, "longitude": 127.0 },
  "placeProposal": {
    "place": { "label": "정문 앞", "latitude": 37.001, "longitude": 127.002 },
    "proposedBy": "PASSENGER",
    "proposedAt": "2026-10-26T05:00:00Z"
  }
}
```

`place`는 항상 현재 확정 장소다. 제안이 있어도 바뀌지 않는다.

## 규칙

- 제안은 한 번에 하나다. 새 제안이 이전 제안을 대체한다. 누가 마지막에 제안했는지는 `proposedBy`(`DRIVER`·`PASSENGER`)로 구분한다.
- 탑승자는 자신의 제안만 지울 수 있다. 운전자가 낸 제안을 탑승자가 지우면 403이다. 운전자는 어느 쪽 제안이든 지울 수 있다.
- 현재 확정 장소와 같은 좌표(약 1m 이내)는 409 `SAME_PLACE`다.
- `ACTIVE`일 때만 제안할 수 있다. 연결 전은 403, 도착 후는 409 `INVALID_STATE`, 종료·만료는 410이다. 지우기는 도착 후에도 가능하다.
- 탑승자가 끊기거나 새로 연결되면 제안을 지운다. 종료된 픽업은 조회 응답에서 제안을 숨긴다.
- 제안은 `pickup` 행의 `proposal_*` 열에 저장한다. 7일 보관 정책으로 픽업과 함께 삭제된다.

## 배포 전 DB 적용

`pickup` 테이블에 열을 추가한다. 서버는 시작할 때 구조를 검사하므로 **새 버전을 배포하기 전에** 적용해야 한다.

```sh
psql -f src/main/resources/db/add-place-proposal.sql
```

열은 모두 NULL을 허용하고 `IF NOT EXISTS`를 쓰므로 반복 적용해도 안전하고 이전 버전과 함께 동작한다. 앱 계정에 `pickup` 테이블의 UPDATE 권한이 이미 있으므로 추가 권한은 필요 없다.
