import { fetchCsrf } from "@/lib/csrf";

/** 로그인 기기 하나 (auth-service GET sessions). 기기 = 브라우저 하나의 SSO 로그인 세션 */
export type Device = {
  id: string;
  current: boolean;
  userAgent: string;
  loginAt: string;
  lastSeenAt: string;
};

/** SSO 세션이 없다(로그인 안 됨·끝남). 화면은 로그인을 거쳐 다시 이 화면으로 온다 */
export class NotSignedInError extends Error {
  constructor() {
    super("로그인이 필요합니다");
  }
}

// 주소는 모두 <base href> 기준 상대 경로 (경로 방식이면 /auth/sessions, 설계 문서 7-4)
const json = { credentials: "same-origin", cache: "no-store" } as const;

export async function listDevices(fetcher: typeof fetch = fetch): Promise<Device[]> {
  const res = await fetcher("sessions", { ...json, headers: { Accept: "application/json" } });
  if (res.status === 401) {
    throw new NotSignedInError();
  }
  if (!res.ok) {
    throw new Error(`기기 목록을 불러오지 못했습니다 (${res.status})`);
  }
  return (await res.json()) as Device[];
}

/** 다른 기기 하나(id) 또는 다른 기기 모두(id 없음)를 로그아웃 */
export async function logoutDevices(id?: string, fetcher: typeof fetch = fetch): Promise<void> {
  const csrf = await fetchCsrf(fetcher);
  const res = await fetcher(id ? `sessions/${encodeURIComponent(id)}` : "sessions", {
    ...json,
    method: "DELETE",
    headers: { [csrf.headerName]: csrf.token },
  });
  if (res.status === 401) {
    throw new NotSignedInError();
  }
  if (!res.ok && res.status !== 404) {
    throw new Error(`로그아웃하지 못했습니다 (${res.status})`);
  }
}

/** 브라우저 문자열을 짧은 이름으로 (정확하지 않아도 된다: 사용자가 기기를 알아볼 정도) */
export function deviceName(userAgent: string): string {
  if (!userAgent) {
    return "알 수 없는 브라우저";
  }
  const browser = /Edg\//.test(userAgent)
    ? "Edge"
    : /Firefox\//.test(userAgent)
      ? "Firefox"
      : /Chrome\//.test(userAgent)
        ? "Chrome"
        : /Safari\//.test(userAgent)
          ? "Safari"
          : /curl\//.test(userAgent)
            ? "curl"
            : "브라우저";
  const os = /Windows/.test(userAgent)
    ? "Windows"
    : /Android/.test(userAgent)
      ? "Android"
      : /iPhone|iPad/.test(userAgent)
        ? "iOS"
        : /Mac OS X/.test(userAgent)
          ? "macOS"
          : /Linux/.test(userAgent)
            ? "Linux"
            : "";
  return os ? `${browser} · ${os}` : browser;
}
