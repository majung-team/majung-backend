# 탑승자 지도용 카카오맵 키

기능: 3.5 탑승자 지도. `GET /api/v1/config` 응답의 `kakaoMapJavaScriptKey`로 웹에 카카오맵 JavaScript 키를 전달한다.

```json
{"developmentToolsEnabled": false, "kakaoMapJavaScriptKey": "..."}
```

- 서버 환경변수 `KAKAO_MAP_JAVASCRIPT_KEY`(설정 `majung.kakao-map.javascript-key`)에서 읽는다. 비어 있으면 `null`이고 웹은 지도 없이 기존 화면을 보여준다.
- JavaScript 키는 브라우저에 노출되는 값이다. 카카오 개발자 콘솔의 **플랫폼 > Web**에 `https://majung.cloud`와 로컬 시험 주소 `http://127.0.0.1:4173`을 등록해 사용 도메인을 제한한다.
- 로그인용 REST API 키·Client Secret·Admin 키는 이 응답에 넣지 않는다.

## 적용 순서

1. 카카오 개발자 콘솔에서 JavaScript 키 확인, Web 플랫폼 도메인 등록, 카카오맵 사용 설정 확인
2. 서버의 `/etc/majung/server.env`에 `KAKAO_MAP_JAVASCRIPT_KEY` 추가 후 서버 재시작
3. `/api/v1/config`에 키가 내려오는지 확인
4. 웹 파일 목록에 `map.js`를 추가한 `deploy/release.py`를 검토한 뒤 `/usr/local/sbin/majung-deploy`로 다시 설치. CI는 이 파일을 갱신하지 않는다
5. Caddy CSP 변경을 서버에 반영하고 웹을 배포

## 확인 범위

`DeploymentHttpTest`에서 설정한 키가 응답에 포함되고 관리자 키는 포함되지 않는 것을 확인했다. `deploy/test_release.py`에서 `map.js`가 포함된 웹 묶음이 허용되는 것을 확인했다. 실제 키로 지도를 띄운 결과는 아직 없다.
