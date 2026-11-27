// Gateway(BFF) 호출. 토큰은 모른다: 같은 출처의 /api 를 세션 쿠키(GW_SESSION)로 부르고, Gateway 가 토큰을 붙여 서비스로 넘긴다 (ADR 0017).
// 주소는 루트 절대 경로(/api/…)로 쓴다. 상대 경로면 경로 방식에서 /admin/api 가 된다 (설계 문서 7-4)

/** 백엔드 오류 응답 (RFC 9457 Problem Details) */
export type ProblemDetail = {
  title?: string;
  status?: number;
  detail?: string;
  errors?: { field: string; message: string }[];
  /** 주문 가격이 바뀌었을 때(409) 요청한 상품의 지금 가격 (ADR 0016). productId 는 UUID 문자열 (ADR 0018) */
  currentPrices?: { productId: string; price: number }[];
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** 응답 본문 (Problem Details 가 아니면 없음) */
    readonly problem?: ProblemDetail,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export async function toApiError(res: Response): Promise<ApiError> {
  let message = `요청에 실패했습니다 (${res.status})`;
  let problem: ProblemDetail | undefined;
  try {
    problem = (await res.json()) as ProblemDetail;
    message = problem.detail ?? problem.title ?? message;
    if (problem.errors?.length) {
      message += ` (${problem.errors.map((e) => `${e.field}: ${e.message}`).join(", ")})`;
    }
  } catch {
    // JSON 이 아니면 기본 메시지
  }
  return new ApiError(res.status, message, problem);
}

/** Gateway 가 쿠키 XSRF-TOKEN 으로 준 CSRF 토큰. 상태를 바꾸는 요청에 헤더·폼 값으로 돌려보낸다 */
export function xsrfToken(cookies: string = document.cookie): string | undefined {
  for (const part of cookies.split(";")) {
    const [name, ...value] = part.trim().split("=");
    if (name === "XSRF-TOKEN") {
      return decodeURIComponent(value.join("="));
    }
  }
  return undefined;
}

/**
 * 로그인 시작 주소. 클라이언트 이름은 쓰지 않는다(auto: Gateway 가 호스트로 고른다). 로그인 뒤 돌아올 곳은 지금 화면의
 * 전체 경로(앞 경로 포함)라 경로 방식에서도 그대로 맞는다 (설계 문서 7-3 "로그인 뒤 돌아갈 곳")
 */
export function loginUrl(returnTo: string): string {
  return `/oauth2/authorization/auto?return_to=${encodeURIComponent(returnTo)}`;
}

/** 화면 이동 (테스트에서 바꿔 끼운다) */
export const browser = {
  currentPath(): string {
    return window.location.pathname + window.location.search;
  },
  assign(url: string): void {
    window.location.assign(url);
  },
};

/** 로그인이 실패해 돌아왔다(Gateway 가 홈의 ?login_error 로 보냄). 다시 자동으로 로그인하지 않는다 */
export class LoginFailedError extends Error {
  constructor() {
    super("로그인하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
    this.name = "LoginFailedError";
  }
}

const LOGIN_ERROR = "login_error";

function splitPath(path: string): { pathname: string; params: URLSearchParams } {
  const i = path.indexOf("?");
  return i < 0
    ? { pathname: path, params: new URLSearchParams() }
    : { pathname: path.slice(0, i), params: new URLSearchParams(path.slice(i + 1)) };
}

/** ?login_error 를 뺀 경로 (다시 로그인한 뒤 돌아올 곳) */
export function withoutLoginError(path: string): string {
  const { pathname, params } = splitPath(path);
  params.delete(LOGIN_ERROR);
  const query = params.toString();
  return query ? `${pathname}?${query}` : pathname;
}

/**
 * 세션이 없거나 끝났다(401). 로그인을 시작한다. SSO 세션이 남아 있으면 화면 없이 바로 돌아온다.
 * 로그인이 실패해 ?login_error 로 돌아온 화면이면 자동으로 다시 가지 않고 LoginFailedError 를 낸다:
 * 토큰 교환이 계속 실패하는데 SSO 세션이 남아 있으면 로그인과 콜백 사이를 끝없이 오가기 때문이다.
 * 사용자가 "다시 로그인"을 누르면 force 로 부른다
 */
export function startLogin(force = false): Promise<never> {
  const path = browser.currentPath();
  if (!force && splitPath(path).params.has(LOGIN_ERROR)) {
    return Promise.reject(new LoginFailedError());
  }
  browser.assign(loginUrl(withoutLoginError(path)));
  // 화면이 넘어갈 때까지 기다린다 (오류 화면이 잠깐 보이지 않게)
  return new Promise<never>(() => {});
}

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (!SAFE_METHODS.has(method)) {
    const token = xsrfToken();
    if (token) {
      headers.set("X-XSRF-TOKEN", token);
    }
  }
  // 백엔드가 응답하지 않을 때 화면이 계속 로딩 상태로 남지 않게
  const timeout = AbortSignal.timeout(10_000);
  const signal = init.signal ? AbortSignal.any([timeout, init.signal]) : timeout;

  const res = await fetch(path, { ...init, method, headers, signal, credentials: "same-origin" });
  if (res.status === 401) {
    return startLogin();
  }
  if (!res.ok) {
    throw await toApiError(res);
  }
  // 204 No Content 등 본문이 없는 응답
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
