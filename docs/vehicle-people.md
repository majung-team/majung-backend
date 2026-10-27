# 차량에서 사람 찾기와 연결 관리

운전자 계정이 연결된 차량 앱은 닉네임·마중 ID 검색과 연결 요청 관리를 사용할 수 있다. 이미 연결된 사람만 표시하는 초대 목록과 구분한다.

| 요청 | 용도 |
| --- | --- |
| GET `/api/v1/vehicles/people/search?query=검색어&page=0` | 닉네임 부분 검색·마중 ID 정확 검색, 페이지당 20명 |
| GET `/api/v1/vehicles/people/connections?page=0` | 보낸 요청·받은 요청·연결된 사람 조회 |
| POST `/api/v1/vehicles/people/connections` | `memberId`, `driverMajungId`로 연결 요청 |
| POST `/api/v1/vehicles/people/connections/{id}/{action}` | `driverMajungId`와 accept/reject/cancel/disconnect 처리 |

모든 요청에 차량 Bearer 인증이 필요하다. 서버의 차량–운전자 계정 연결에서 요청자를 결정하며 클라이언트가 임의의 운전자 memberId를 지정하지 않는다. 쓰기 요청의 driverMajungId는 화면에 표시한 계정이 여전히 같은지 확인하는 값이다. 계정이 바뀌면 거절한다.

사람 연결만으로 위치를 공유하거나 픽업을 시작하지 않는다. 보낸 연결 요청을 본인이 승인할 수 없으며 상대방이 승인해야 한다. 같은 요청을 다시 보내면 기존 연결 상태를 반환한다. 연결 해제·권한 철회·카카오 연결 해제를 검사하고 기존 검색 길이·페이지 범위·요청 제한과 재요청 대기 정책을 유지한다. 새 테이블이나 마이그레이션은 필요하지 않다.
