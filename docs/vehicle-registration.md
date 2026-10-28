# 일반 사용자 연결

> 내부 관리자 코드·권한 철회 안내다. 일반 운전자 첫 연결은 [QR 최초 연결](vehicle-enrollment.md)을 따른다.

일반 사용자는 [휴대전화 QR 연결](vehicle-enrollment.md)을 사용한다. 아래 관리자 코드는 내부 시험·관리 절차이며, 일반 차량 앱 화면에는 표시하지 않는다.

# 차량 최초 등록과 권한 철회

등록 코드는 차량마다 최초 한 번 사용한다. 이후에는 차량별 권한으로 픽업을 만든다. 차량 권한과 픽업 운전자·탑승자 토큰은 서로 바꿔 사용할 수 없다.

## 관리자

서버 환경에 32자 이상 무작위 `MAJUNG_ADMIN_KEY`를 설정한다. 이 값은 서버에만 두고 APK·웹·Git에 넣지 않는다. 이전 `MAJUNG_CREATION_KEY`를 보내는 방식으로는 픽업을 만들 수 없다.

1. `POST /api/v1/admin/vehicle-registration-codes`에 관리자 Bearer 권한을 보낸다.
2. 응답의 `code`를 차량에 전달한다. 10분 뒤 만료되며 한 번만 사용된다. 응답에는 만료 시각과 코드 관리 ID도 있다.
3. 차량 등록 후 `vehicleId`를 기록한다. 이 값으로 `POST /api/v1/admin/vehicles/{vehicleId}/revocation`을 호출하면 해당 차량 권한을 철회한다.

철회는 같은 차량의 진행 중 픽업을 취소하고 저장된 차량 위치·ETA를 즉시 지운다. 다른 차량은 영향을 받지 않는다. 철회 요청을 반복해도 같은 결과를 반환한다. 다시 쓰려면 새 코드로 등록한다.

관리자 작업은 개인 관리 환경에서 실행한다. 초대 토큰처럼 URL 쿼리에 키를 넣지 않는다. 모든 응답은 `Cache-Control: no-store`다.

## 차량

- `POST /api/v1/vehicles/registrations`: `code`, `displayName`을 JSON으로 보낸다. 응답은 `vehicleId`, `vehicleToken`이다. 권한 원문은 등록 응답에서 한 번만 발급한다.
- `GET /api/v1/vehicles/me`: 차량 Bearer 권한의 유효성을 확인한다. 토큰 원문은 반환하지 않는다.
- `POST /api/v1/pickups`: 차량 Bearer 권한과 기존 `place` JSON을 보낸다. 이후 픽업 조작에는 이 응답의 별도 `driverToken`을 사용한다.

등록 요청의 응답을 잃으면 같은 코드를 자동 재시도하지 않는다. 이미 소비됐을 수 있으므로 관리자가 `GET /api/v1/admin/vehicle-registration-codes/{registrationCodeId}`로 사용 여부와 발급된 vehicleId를 확인하고 필요하면 권한 철회 후 새 코드를 발급한다. 조회 응답에는 코드·차량 토큰 원문이 없다. 서버 DB에는 코드·차량 권한의 SHA-256 해시만 보관한다.

`developmentToolsEnabled=true`인 로컬 개발 모드만 등록 없는 생성을 허용한다. deploy 프로필은 개발 모드를 끄고 관리자 키·요청 제한·보관 정리를 필수로 검사한다.

## 오류

| code | 처리 |
| --- | --- |
| REGISTRATION_CODE_UNAVAILABLE | 만료·사용 완료·존재하지 않는 코드. 관리자에게 새 코드 요청 |
| VEHICLE_REGISTRATION_REQUIRED | 최초 등록 필요 |
| VEHICLE_CREDENTIAL_INVALID | 차량 권한 불일치. 관리자 확인 후 재등록 |
| VEHICLE_REVOKED | 철회된 차량. 새 픽업 생성 금지 |
| ADMIN_REQUIRED / ADMIN_DISABLED | 관리자 키 또는 서버 설정 확인 |
| RETRY_REQUEST | 동시 처리 충돌. 상태 확인 후 다시 요청 |

## DB 반영

새 DB에는 `src/main/resources/db/initial-schema.sql`을 한 번 실행한다. 기존 배포 DB는 API 중지·백업 후 `db/upgrade-vehicle-registration.sql`을 한 번 실행하고 새 JAR를 시작한다. 기존 픽업은 vehicleId가 없으며 기존 만료·종료 정책을 따른다. 과거 픽업을 임의의 차량에 연결하지 않는다.

H2에서는 로컬 update 모드로 개발할 수 있지만 PostgreSQL 배포는 validate 모드다. 배포 DB에 자동 스키마 변경을 켜지 않는다.

## 확인한 범위

실제 HTTP 시험으로 코드 만료·재사용·동시 소비, 등록 없는 생성 차단, 등록 후 반복 생성, 픽업 토큰과 차량 토큰의 용도 분리, 개별 철회와 진행 중 공유 중단·좌표 삭제를 확인한다. 배포 DB 스키마 검증도 초기 SQL을 사용한다. 실제 GCP 반영과 휴대전화 통합 시험은 별도 기록이 있어야 완료다.
