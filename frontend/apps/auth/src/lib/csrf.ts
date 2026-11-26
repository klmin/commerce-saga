import { useCallback, useEffect, useState } from "react";

/** auth-service 가 SSO 세션에 둔 CSRF 토큰 (GET csrf 의 본문, 설계 문서 4절) */
export type CsrfToken = { parameterName: string; token: string; headerName: string };

/**
 * CSRF 토큰을 받는다. 주소는 앞 `/` 없는 상대 경로(`csrf`)라 `<base href>` 기준이다: 호스트 방식 `/csrf`, 경로 방식 `/auth/csrf`
 * (설계 문서 7-4). 토큰은 세션 쿠키(AUTH_SESSION)에 묶여 있어 같은 출처로 쿠키와 함께 부른다
 */
export async function fetchCsrf(fetcher: typeof fetch = fetch): Promise<CsrfToken> {
  const res = await fetcher("csrf", { credentials: "same-origin", cache: "no-store" });
  if (!res.ok) {
    throw new Error(`csrf ${res.status}`);
  }
  const body: unknown = await res.json();
  if (
    typeof body !== "object" ||
    body === null ||
    typeof (body as CsrfToken).parameterName !== "string" ||
    typeof (body as CsrfToken).token !== "string"
  ) {
    throw new Error("csrf 응답 형식");
  }
  const { parameterName, token, headerName } = body as Partial<CsrfToken>;
  // 헤더 이름은 fetch 로 보내는 요청(기기 로그아웃)에 쓴다. 폼은 parameterName
  return {
    parameterName: parameterName!,
    token: token!,
    headerName: typeof headerName === "string" ? headerName : "X-CSRF-TOKEN",
  };
}

export type CsrfState =
  | { status: "loading" }
  | { status: "ready"; csrf: CsrfToken }
  | { status: "error"; retry: () => void };

/** 화면을 열 때 한 번 받는다. 실패하면 다시 시도할 수 있다 */
export function useCsrf(): CsrfState {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<
    { status: "loading" } | { status: "ready"; csrf: CsrfToken } | { status: "error" }
  >({ status: "loading" });
  const retry = useCallback(() => {
    setState({ status: "loading" });
    setAttempt((n) => n + 1);
  }, []);

  useEffect(() => {
    let active = true;
    fetchCsrf().then(
      (csrf) => active && setState({ status: "ready", csrf }),
      () => active && setState({ status: "error" }),
    );
    return () => {
      active = false;
    };
  }, [attempt]);

  return state.status === "error" ? { status: "error", retry } : state;
}
