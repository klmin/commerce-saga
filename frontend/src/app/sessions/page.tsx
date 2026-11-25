import type { Metadata } from "next";
import { headers } from "next/headers";
import { Suspense } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiError } from "@/lib/api/client";
import { listSessions } from "@/lib/auth/backend";
import { clientInfoFrom } from "@/lib/auth/client";
import { accessToken, refreshToken } from "@/lib/auth/server-session";
import { redirectAfterUnauthorized, redirectToLogin } from "@/lib/auth/unauthorized";
import { deviceName } from "@/lib/auth/device";
import { formatTime } from "@/lib/orders/format";

import { ListSkeleton } from "../loading-skeletons";
import { ToneBadge } from "../tone-badge";
import { logoutAllAction, revokeSessionAction } from "./session-actions";

export const metadata: Metadata = { title: "로그인 기기 · commerce-saga" };

const ERRORS: Record<string, string> = {
  invalid: "잘못된 요청입니다.",
  failed: "처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.",
};

async function SessionList() {
  const token = await accessToken();
  if (!token) {
    return redirectToLogin();
  }
  let sessions;
  try {
    sessions = await listSessions(token, await refreshToken(), clientInfoFrom(await headers()));
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      return redirectAfterUnauthorized();
    }
    throw e;
  }
  // 이 기기를 맨 위에, 나머지는 최근 사용 순
  const ordered = [...sessions].sort(
    (a, b) => Number(b.current) - Number(a.current) || b.lastUsedAt.localeCompare(a.lastUsedAt),
  );
  return (
    <ul className="flex flex-col divide-y">
      {ordered.map((s) => (
        <li key={s.sessionId} className="flex items-center gap-4 py-3">
          <div className="flex flex-1 flex-col">
            <span className="font-medium">{deviceName(s.agent)}</span>
            <span className="text-sm text-muted-foreground">
              로그인 {formatTime(s.createdAt)} · 최근 사용 {formatTime(s.lastUsedAt)} · 만료{" "}
              {formatTime(s.expiresAt)}
            </span>
          </div>
          {s.current ? (
            <ToneBadge label="이 기기" tone="success" />
          ) : (
            <form action={revokeSessionAction}>
              <input type="hidden" name="sessionId" value={s.sessionId} />
              <Button type="submit" variant="outline" size="sm">
                로그아웃
              </Button>
            </form>
          )}
        </li>
      ))}
    </ul>
  );
}

// searchParams 는 요청 시점 데이터라 Suspense 안에서 await 한다 (Cache Components). 그래야 카드 틀이 먼저 뜬다
async function ErrorMessage({ searchParams }: Pick<PageProps<"/sessions">, "searchParams">) {
  const error = (await searchParams).error;
  const message = typeof error === "string" ? ERRORS[error] : undefined;
  if (!message) {
    return null;
  }
  return (
    <p role="alert" className="text-sm text-destructive">
      {message}
    </p>
  );
}

export default function SessionsPage({ searchParams }: PageProps<"/sessions">) {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>로그인 기기</CardTitle>
          <form action={logoutAllAction}>
            <Button type="submit" variant="outline" size="sm">
              모든 기기에서 로그아웃
            </Button>
          </form>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          <p className="text-sm text-muted-foreground">
            로그인할 때마다 기기 하나가 생깁니다(refresh token). 다른 기기를 로그아웃하면 그 기기는
            지금 쓰는 access token 이 끝나는 대로(최대 15분) 다시 로그인해야 합니다.
          </p>
          <Suspense fallback={null}>
            <ErrorMessage searchParams={searchParams} />
          </Suspense>
          <Suspense fallback={<ListSkeleton rows={2} />}>
            <SessionList />
          </Suspense>
        </CardContent>
      </Card>
    </main>
  );
}
