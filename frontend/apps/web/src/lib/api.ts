// Gateway(BFF) 호출. 토큰은 모른다: 같은 출처의 /api 를 세션 쿠키(GW_SESSION)로 부르고, Gateway 가 토큰을 붙여 서비스로 넘긴다 (ADR 0017).
// 주소는 루트 절대 경로(/api/…)로 쓴다. 상대 경로면 경로 방식에서 /admin/api 가 된다 (설계 문서 7-4)

/** 백엔드 오류 응답 (RFC 9457 Problem Details) */
export type ProblemDetail = {
  title?: string;
  status?: number;
  detail?: string;
  errors?: { field: string; message: string }[];
  /** 주문 가격이 바뀌었을 때(409) 요청한 상품의 지금 가격 (ADR 0016) */
  currentPrices?: { productId: number; price: number }[];
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

/** 세션이 없거나 끝났다(401). 로그인을 시작한다. SSO 세션이 남아 있으면 화면 없이 바로 돌아온다 */
export function startLogin(): Promise<never> {
  browser.assign(loginUrl(browser.currentPath()));
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
