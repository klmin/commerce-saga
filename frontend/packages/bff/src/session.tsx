import { Button } from "@commerce/ui/button";
import { LoadError, Page } from "@commerce/ui/page";
import { ListSkeleton } from "@commerce/ui/skeletons";
import { QueryClient, useQuery } from "@tanstack/react-query";
import type { FormEvent, ReactNode } from "react";

import { ApiError, LoginFailedError, startLogin, xsrfToken } from "./api";
import { basePath } from "./base";
import { getMe, type Me } from "./me";

/** 화면들의 TanStack Query 기본값 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // 4xx·로그인 실패는 다시 해도 같다. 5xx·네트워크 오류만 한 번 더
        retry: (count, error) =>
          count < 1 &&
          !(error instanceof LoginFailedError) &&
          !(error instanceof ApiError && error.status >= 400 && error.status < 500),
        refetchOnWindowFocus: false,
      },
    },
  });
}

/** 지금 로그인한 사용자 (Gateway /api/me). 세션이 없으면 api() 가 로그인을 시작한다 */
export function useMe() {
  return useQuery({ queryKey: ["me"], queryFn: getMe, staleTime: 5 * 60_000 });
}

/**
 * 로그인해야 보이는 화면. 처음에 /api/me 로 세션을 확인하고, 없으면(401) api() 가 이 주소로 돌아오게 로그인을 시작한다.
 * 로그인이 실패해 돌아왔으면(?login_error) 자동으로 다시 가지 않고 "다시 로그인" 을 보여 준다.
 * allow 가 false 면(예: 관리자 화면에 관리자가 아닌 사용자) denied 를 그린다. 권한 검사는 Gateway·서비스가 토큰으로 한다
 */
export function SessionGate({
  children,
  allow = () => true,
  denied,
}: {
  children: ReactNode;
  allow?: (me: Me) => boolean;
  denied?: ReactNode;
}) {
  const me = useMe();
  if (me.isSuccess) {
    return allow(me.data) ? children : denied;
  }
  if (me.error instanceof LoginFailedError) {
    return (
      <Page>
        <div role="alert" className="flex flex-col items-start gap-3">
          <p className="text-sm text-destructive">{me.error.message}</p>
          <Button type="button" onClick={() => void startLogin(true)}>
            다시 로그인
          </Button>
        </div>
      </Page>
    );
  }
  if (me.isError) {
    return (
      <Page>
        <LoadError error={me.error} retry={() => me.refetch()} />
      </Page>
    );
  }
  return (
    <Page>
      <ListSkeleton />
    </Page>
  );
}

/**
 * 로그아웃: Gateway 에 폼 POST(/logout, 루트 절대 경로). Gateway 가 BFF 세션을 지우고 SAS OIDC 로그아웃(SSO 전체)을 거쳐
 * return_to(이 화면의 홈)로 돌려보낸다 (설계 문서 7-3). CSRF 토큰은 쿠키 XSRF-TOKEN 을 제출할 때 읽어 넣는다
 */
export function LogoutForm() {
  function onSubmit(event: FormEvent<HTMLFormElement>) {
    const input = event.currentTarget.elements.namedItem("_csrf");
    if (input instanceof HTMLInputElement) {
      input.value = xsrfToken() ?? "";
    }
  }
  return (
    <form method="post" action="/logout" onSubmit={onSubmit}>
      <input type="hidden" name="_csrf" defaultValue="" />
      <input type="hidden" name="return_to" value={basePath()} />
      <Button type="submit" variant="outline" size="sm">
        로그아웃
      </Button>
    </form>
  );
}
