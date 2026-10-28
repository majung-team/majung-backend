# 차량과 운전자 계정 연결

일반 운전자는 [QR 최초 연결](vehicle-enrollment.md)로 차량 등록과 계정 연결을 함께 시작한다. 이 문서는 이미 등록된 차량의 계정 연결 절차다. 관리자 코드 등록은 내부 관리 방식이며 일반 시작 단계가 아니다. 탑승자 사람 연결·마중 요청은 운전자 계정 연결 QR을 사용하지 않는다.

차량 QR → /account에서 카카오 로그인·닉네임 → 휴대전화에서 연결 요청 → 차량에서 닉네임·마중 ID 확인 후 승인 순서다. 휴대전화 요청만으로는 연결하지 않는다.

- 차량당 운전자 계정 한 개, 계정당 여러 차량을 허용한다. 탑승자 수와 무관하다.
- QR은 5분 뒤 만료된다. 새 QR은 이전 QR을 무효화한다. 원본 코드는 DB에 저장하지 않는다.
- 같은 QR을 다른 계정이 먼저 요청하면 409, 다른 차량에서 확인하려 하면 410이다.
- 이미 연결된 차량은 기존 계정 연결을 먼저 해제해야 한다.
- 휴대전화는 코드 fragment를 바로 주소에서 지우고 OAuth 복귀를 위해 해당 탭의 sessionStorage에만 잠시 보관한다. localStorage·로그에 넣지 않는다.
- 차량 권한은 Android Keystore 저장소에서 유지한다. 계정 연결 QR 코드는 차량 메모리에만 둔다.

차량 API: POST /api/v1/vehicles/account-link/challenges, GET /api/v1/vehicles/account-link, POST /api/v1/vehicles/account-link/confirm, DELETE /api/v1/vehicles/account-link.

계정 API: GET /api/v1/vehicle-links, POST /api/v1/vehicle-links/requests, GET /api/v1/vehicle-links/requests/{id}, POST /api/v1/vehicle-links/requests/{id}/cancel, DELETE /api/v1/vehicle-links/{vehicleId}. 계정 변경에는 세션·CSRF가 필요하다.

계정 연결 해제는 차량 최초 등록이나 기존 초대 픽업을 대신 철회하는 기능이 아니다. 관리자 차량 권한 철회는 기존 등록 API를 사용한다.
