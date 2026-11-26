# 시나리오 19. SSO·로그아웃·로그인 기기·클라이언트 시크릿 교체 (후속 5)

인증을 Spring Authorization Server(SAS) + Gateway BFF + 화면 SPA 3개로 바꾼 뒤([ADR 0017](../adr/0017-auth-sas-gateway-bff-spa.md), [설계](../auth-sas-bff-design.md))의 로그인 흐름을 확인한다. 시나리오 9 의 9a~9e(자체 refresh token 회전·재사용 탐지)를 대신한다.

| | 확인하는 것 |
|---|---|
| 19a | 로그인: 화면 → Gateway(`/oauth2/authorization/auto`) → SAS 인가 → 인증 화면 `/signin` → 폼 POST `login` → 인가 코드 → Gateway 세션(`GW_SESSION`) → 가려던 화면. 브라우저 JS 는 토큰·세션 쿠키를 볼 수 없다 |
| 19b | SSO: 사용자 화면에서 로그인한 브라우저는 관리자 화면(`gateway-admin`)에 로그인 화면 없이 들어간다(SSO 세션 `AUTH_SESSION`) |
| 19c | 로그아웃: Gateway 세션 삭제 → SAS OIDC 로그아웃(`/connect/logout`) → 이 브라우저의 SSO 세션과 **이 기기의** 인가 기록(refresh token) 삭제 → 다시 열면 로그인 화면. 다른 기기는 그대로 |
| 19d | 로그인 기기: 기기 = 브라우저 하나의 SSO 세션. 목록(지금 기기 표시), 다른 기기 하나·모두 로그아웃 → 그 브라우저의 SSO 세션과 refresh token 삭제(그 기기의 refresh `invalid_grant`, 다시 열면 로그인 화면). 지금 기기는 기기 화면에서 지우지 않는다(400), 남의·없는 기기 404, CSRF 헤더 없으면 403, 로그인 안 했으면 401 |
| 19e | 클라이언트 시크릿 교체: Gateway·auth-service 가 같은 출처(외부 변수 → 시크릿 파일)에서 읽고, auth-service 는 시작할 때 해시를 비교해 바뀌었으면 갱신한다. 둘 다 새 값으로 다시 띄우면 로그인이 된다 |

## 재현·근거

| | 어디서 | 무엇을 |
|---|---|---|
| 19a·19c·19d | CI `browser-e2e` (`frontend/apps/web/e2e/auth.spec.ts`) | 실제 브라우저로 로그인·실패 문구·`document.cookie` 에 세션 쿠키 없음, 로그아웃 후 로그인 화면, 두 브라우저(컨텍스트)로 로그인 → 한쪽에서 "다른 기기 모두 로그아웃" → 다른 쪽이 기기 화면을 열면 로그인 화면 |
| 19b | CI `browser-e2e` (`frontend/apps/admin/e2e/*`) | 관리자 화면 로그인(`gateway-admin`), 관리자 아님 화면 |
| 19a·19c | CI `compose`·`k8s` (`scripts/smoke-bff-login.sh`) | 앞단 프록시(Caddy·Envoy)를 지나는 BFF 로그인 한 바퀴를 경로 방식·호스트 방식으로 |
| 19c·19d | `services/auth-service` `OAuthFlowIntegrationTest` (Postgres + Redis) | SSO 로그아웃은 이 기기의 refresh 만 막는다, 기기 목록·다른 기기 로그아웃(그 기기 refresh `400`, 지금 기기 `200`, 그 기기 인가 요청은 로그인 화면), 다른 기기 모두 로그아웃, 지금 기기 400·없는 기기 404·CSRF 없음 403, 로그인 안 함 401·`/sessions/login` 은 로그인 화면을 거쳐 `/devices` 로 |
| 19d | `DeviceServiceTest` | SSO 세션이 끝나도 refresh token 이 살아 있으면 기기가 보이고(화면은 refresh 로 로그인을 이어간다) 둘 다 끝나면 사라진다, 정렬(지금 기기 → 최근 사용) |
| 19c | `services/gateway` `GatewayBffTest` | Gateway 로그아웃이 세션을 지우고 SAS OIDC 로그아웃으로 보낸다, refresh 가 거절되면 세션을 끝내고 API 401 |
| 19e | `RegisteredClientSyncTest` | 시크릿이 바뀌면 해시를 바꾼다(BCrypt 는 매번 해시가 달라 `matches` 로 비교), 같으면 그대로, 외부 값도 개발용 디렉터리도 없으면 기동 실패 |

로컬에서 손으로: 인프라 + 서비스 + Gateway + 화면 셋(`pnpm dev:web`·`dev:admin`·`dev:auth`, [frontend/README](../../frontend/README.md)).

1. 크롬 창 하나(일반)와 시크릿 창 하나에서 http://localhost:5173 → 로그인(`user1` / `local-dev-pass`)
2. 일반 창 헤더의 "로그인 기기" → http://localhost:5175/devices 에 기기 둘, 일반 창이 "이 기기"
3. 시크릿 창 기기를 "로그아웃" → 시크릿 창에서 "로그인 기기"를 누르면 로그인 화면. 화면 API 는 이미 받은 access token 이 끝날 때까지(최대 15분) 동작하고, 그 뒤 Gateway 의 갱신이 거절되어 401 → 로그인 화면
4. 시크릿 교체(19e): `~/.commerce-saga/oauth-client-gateway-web.secret` 을 지우고 auth-service·Gateway 를 다시 띄운다 → 새 값이 만들어지고 auth-service 가 해시를 바꾼다 → 로그인 된다. 한쪽만 다시 띄우면 토큰 교환이 `invalid_client` 로 실패한다(설계 문서 6절: 둘이 같은 출처를 읽어야 한다)

## 설계 메모

- **기기 = SSO 세션.** 로그인(폼·가입 후 로그인)할 때 세션 고정 방지(세션 id 교체) 다음에 기기 id 를 만들어 세션에 두고, `auth:device:<userId>`(Redis hash: 기기 id → 세션 id·로그인 시각·브라우저)에 남긴다. SAS 인가 기록에는 인가 요청 때 그 세션의 기기 id 를 속성 `device_id` 로 붙인다(토큰 교환·갱신은 같은 기록을 이어 써서 남는다)
- **기기의 수명**: SSO 세션은 30분 유휴로 끝나지만 화면은 Gateway 세션의 refresh token(최대 14일)으로 로그인을 이어간다. 그래서 기기는 SSO 세션이나 그 기기의 refresh token 중 하나라도 살아 있으면 보이고, 마지막 사용 시각은 둘 중 늦은 것이다. 둘 다 끝난 기기는 목록을 볼 때 지운다
- **다른 기기 로그아웃의 한계**: 그 브라우저의 Gateway 세션은 Gateway 소유(서비스 경계)라 auth-service 가 지우지 않는다. 이미 받은 access token 은 끝날 때까지(최대 15분) 쓰이고, 그 뒤 refresh 가 거절되면 Gateway 가 세션을 끝낸다. 즉시 끊어야 하면 access token 수명을 줄이거나 Gateway 에 세션 폐기 이벤트를 받게 하는 방법이 있다(지금은 하지 않음)
- **OIDC 로그아웃은 이 기기만**: 예전(5-1)엔 그 사용자의 인가 기록을 모두 지웠다. 기기가 생겨 이 브라우저의 기록만 지우고, 다른 기기 정리는 기기 화면에서 한다. 기기 id 가 없는 세션(기기를 남기기 전에 로그인한 세션)이면 예전처럼 모두 지운다
- **기기 화면 위치**: 인증 화면 `/devices`. 인증 화면이 auth-service 를 SSO 세션 쿠키로 바로 부르므로(같은 출처) 토큰·Gateway 를 거치지 않는다. 사용자 화면은 Gateway `GET /api/me` 의 `devicesUrl`(= SAS 공개 주소 + `/sessions/login`)로 링크한다. `/sessions/login` 은 SSO 세션이 있으면 `/devices` 로, 없으면 로그인 화면을 거쳐 돌아온다
- API: `GET /sessions`, `DELETE /sessions/{기기 id}`, `DELETE /sessions`(다른 기기 모두). 응답에 세션 id(쿠키 값)를 내보내지 않는다. 지우는 요청은 CSRF 헤더(`GET csrf` 의 `headerName`). 앞단 프록시는 `/sessions` 를 auth-service 로 보낸다(설계 문서 7-1)
