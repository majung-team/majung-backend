# 도착 임박 웹 푸시

기능: 7.1 도착 임박 푸시. 차량이 탑승자 장소까지 약 3분 이내가 되면 웹 푸시를 한 번 보낸다. 화면을 닫거나 다른 앱을 쓰는 중에도 받을 수 있다. VAPID 키를 설정하지 않으면 기능은 꺼지고 기존 동작은 바뀌지 않는다.

## 동작

- 탑승자 브라우저가 알림을 허용하고 구독하면 서버가 구독 주소(endpoint)를 픽업 하나에 하나만 저장한다.
- 차량 정보 갱신에서 `ACTIVE`이고 `durationSeconds`가 `majung.push.arrival-seconds`(기본 180) 이하가 되면 한 번 보낸다.
- 보냈다는 표시(`notified_at`)는 DB의 조건부 갱신으로 남긴다. 갱신이 겹쳐도 한 번만 보내고, 구독을 새로 해도 다시 보내지 않는다.
- 푸시에는 내용을 싣지 않는다. 문구는 브라우저의 서비스 워커에 고정되어 있어 이름·위치가 푸시 서비스를 거치지 않는다. 그래서 내용 암호화도 쓰지 않는다.
- 발송은 DB 저장이 끝난 뒤 별도 스레드에서 한다. 푸시 서비스가 느려도 차량 정보 전송이 늦어지지 않는다. 실패하면 다시 시도하지 않는다(알림 유효 시간 TTL 10분).
- 푸시 서비스가 404·410으로 응답하면 해당 구독을 지운다.
- 탑승자가 끊기거나 초대가 다시 발급되면, 픽업이 끝나면, 보관 기간이 지나면 구독을 지운다. 끊긴 탑승자의 브라우저가 다음 탑승자의 알림을 받지 않는다.

## API

| 요청 | 인증 | 응답 |
| --- | --- | --- |
| `PUT /api/v1/pickups/{id}/arrival-push` `{"endpoint": "https://..."}` | 탑승자 토큰 | 200 `{"subscribed": true}` |
| `DELETE /api/v1/pickups/{id}/arrival-push` | 탑승자 토큰 | 200 `{"subscribed": false}` |
| `PUT·DELETE /api/v1/ride-requests/{id}/arrival-push` | 로그인 세션·CSRF, 시작된 내 픽업 | 위와 같음 |
| `GET /api/v1/config` | 없음 | `webPushPublicKey`: 키가 있으면 공개 키, 없으면 `null` |

| 오류 | 의미 |
| --- | --- |
| 400 `INVALID_PUSH_ENDPOINT` | 허용하지 않는 주소 |
| 403 | 탑승자가 아니거나 위치 공유 중이 아님 |
| 404 `PUSH_DISABLED` | 서버에 VAPID 키가 없음 |

서버가 구독 주소로 직접 요청을 보내므로 주소는 `https`이고 포트 443인 아래 푸시 서비스만 받는다. 내부 주소나 임의의 서버를 가리킬 수 없다. 리다이렉트도 따라가지 않는다.

`fcm.googleapis.com`(Chrome·Android), `push.services.mozilla.com`(Firefox), `push.apple.com`(Safari·홈 화면 웹앱), `notify.windows.com`

## 설정

1. 키 만들기: `java deploy/VapidKeys.java`. 공개 키 87자와 개인 키 43자(URL-safe Base64)가 출력된다. 개인 키는 서버에만 둔다.
2. `/etc/majung/server.env`에 `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT`(`mailto:` 또는 `https://` 연락처)를 넣고 서버를 재시작한다. 세 값 중 일부만 있거나 키 형식이 틀리면 서버가 시작하지 않는다.
3. 키를 바꾸면 기존 구독은 새 키로 보낼 수 없다. 탑승자가 다시 구독해야 한다.

## DB 적용

새 테이블 `arrival_push`가 필요하다. 신규 DB는 `initial-schema.sql`에 포함돼 있다. 기존 DB는 API를 멈추고 백업한 뒤 `db/upgrade-arrival-push.sql`을 한 번 실행하고, 실행 계정에 이 테이블의 SELECT·INSERT·UPDATE·DELETE 권한을 준다. 앱 배포가 SQL을 자동 실행하지 않는다. 키를 넣지 않으면 이 테이블을 쓰지 않지만, 배포 프로필은 시작할 때 테이블 모양을 검사하므로 새 JAR를 시작하기 전에 적용해야 한다.

## 확인 범위

- 자동 시험: VAPID 서명(공개 키로 검증)·헤더·빈 본문, 404·410·500·리다이렉트 처리, 주소 허용 규칙, 3분 경계 한 번 발송, 재구독 후 재발송 없음, 끊기·종료 시 삭제, 구독 해제, 기능 꺼짐, 마중 요청 계정 구독.
- 실제 푸시 서비스(FCM·Mozilla·Apple)로 보내 본 결과는 없다. 실제 키와 브라우저가 필요하다.
