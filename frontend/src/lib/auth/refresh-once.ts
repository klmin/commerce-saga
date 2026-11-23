import "server-only";

import type { Session } from "./session";

// 같은 refresh token 으로 거의 동시에 들어온 요청(여러 탭, 한 화면의 병렬 요청)은 백엔드를 한 번만 부르고 결과를 나눠 쓴다.
// 백엔드는 이미 회전된 옛 refresh token 을 재사용(탈취)으로 보고 그 기기 세션을 버리므로(ADR 0008), 여기서 한 번으로 묶는다.
// 끝난 뒤에도 잠깐 결과를 남겨 둔다: 첫 응답의 새 쿠키가 브라우저에 닿기 전에 옛 쿠키로 온 요청도 같은 결과를 받는다.
// 프로세스(인스턴스) 하나 안에서만 묶인다. 여러 인스턴스 사이는 백엔드의 동시 refresh 유예(20초)가 받는다
const KEEP_RESULT_MS = 10_000;

const inflight = new Map<string, Promise<Session>>();

export function refreshOnce(
  refreshToken: string,
  doRefresh: (token: string) => Promise<Session>,
): Promise<Session> {
  const existing = inflight.get(refreshToken);
  if (existing) {
    return existing;
  }
  const promise = doRefresh(refreshToken);
  inflight.set(refreshToken, promise);
  const forget = () => {
    setTimeout(() => {
      if (inflight.get(refreshToken) === promise) {
        inflight.delete(refreshToken);
      }
    }, KEEP_RESULT_MS).unref?.();
  };
  // 실패는 바로 잊는다 (백엔드가 잠깐 죽었을 때 다음 요청이 다시 시도하게)
  promise.then(forget, () => {
    if (inflight.get(refreshToken) === promise) {
      inflight.delete(refreshToken);
    }
  });
  return promise;
}

/** 테스트용 */
export function resetRefreshOnce(): void {
  inflight.clear();
}
