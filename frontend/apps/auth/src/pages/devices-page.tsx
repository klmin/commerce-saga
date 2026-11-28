import { Brand } from "@commerce/ui/brand";
import { Button } from "@commerce/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@commerce/ui/card";
import { useCallback, useEffect, useState } from "react";

import {
  deviceName,
  listDevices,
  logoutDevices,
  NotSignedInError,
  type Device,
} from "@/lib/devices";

type State =
  | { status: "loading" }
  | { status: "ready"; devices: Device[] }
  | { status: "error"; message: string };

/** 페이지 이동 (화면 밖 주소: auth-service 의 로그인 거쳐 돌아오기). 렌더마다 새로 만들지 않게 밖에 둔다 */
const navigate = (url: string) => window.location.assign(url);

const time = new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short" });

/**
 * 로그인 기기 (후속 5-6). 기기 = 이 계정으로 로그인한 브라우저(SSO 세션). auth-service 를 SSO 세션 쿠키로 바로 부른다.
 * 다른 기기를 로그아웃하면 그 브라우저는 SSO 세션과 refresh token 을 잃고, 이미 받은 access token 이 끝나면(최대 15분) 다시 로그인해야 한다.
 * 이 기기는 화면의 로그아웃 버튼으로 로그아웃한다. 로그인하지 않았으면 로그인을 거쳐 이 화면으로 돌아온다
 */
export function DevicesPage({
  goTo = navigate,
}: {
  /** 화면 이동 (테스트에서 바꾼다) */
  goTo?: (url: string) => void;
}) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [busy, setBusy] = useState(false);
  // 바뀌면 목록을 다시 불러온다 (다시 시도·로그아웃 뒤)
  const [version, setVersion] = useState(0);
  const reload = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    let active = true;
    listDevices().then(
      (devices) => active && setState({ status: "ready", devices }),
      (e: unknown) => {
        if (!active) return;
        if (e instanceof NotSignedInError) {
          // auth-service 가 로그인 화면을 거쳐 기기 화면으로 다시 보낸다
          goTo("sessions/login");
          return;
        }
        setState({
          status: "error",
          message: e instanceof Error ? e.message : "불러오지 못했습니다.",
        });
      },
    );
    return () => {
      active = false;
    };
  }, [version, goTo]);

  async function logout(id?: string) {
    setBusy(true);
    try {
      await logoutDevices(id);
      reload();
    } catch (e) {
      if (e instanceof NotSignedInError) {
        goTo("sessions/login");
        return;
      }
      setState({
        status: "error",
        message: e instanceof Error ? e.message : "로그아웃하지 못했습니다.",
      });
    } finally {
      setBusy(false);
    }
  }

  const others = state.status === "ready" ? state.devices.filter((d) => !d.current) : [];

  return (
    <div className="flex min-h-svh flex-1 flex-col bg-muted/30">
      <main className="mx-auto flex w-full max-w-xl flex-1 flex-col gap-6 px-4 py-8">
        <Brand />
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold">로그인 기기</h1>
          <p className="text-sm text-muted-foreground">
            이 계정으로 로그인한 브라우저입니다. 모르는 기기는 로그아웃하세요. 로그아웃한 기기는
            15분 안에 로그인이 끝납니다.
          </p>
        </div>
        {state.status === "loading" && (
          <p className="text-sm text-muted-foreground">불러오는 중…</p>
        )}
        {state.status === "error" && (
          <div role="alert" className="flex flex-col items-start gap-2 text-sm">
            <p className="text-destructive">{state.message}</p>
            <button
              type="button"
              className="underline underline-offset-4"
              onClick={() => {
                setState({ status: "loading" });
                reload();
              }}
            >
              다시 시도
            </button>
          </div>
        )}
        {state.status === "ready" && (
          <ul aria-label="로그인 기기" className="flex flex-col gap-3">
            {state.devices.map((d) => (
              <li key={d.id}>
                <Card>
                  <CardHeader className="flex flex-row items-center justify-between gap-4">
                    <CardTitle className="text-base">
                      {deviceName(d.userAgent)}
                      {d.current && (
                        <span className="ml-2 rounded bg-secondary px-2 py-0.5 text-xs font-normal">
                          이 기기
                        </span>
                      )}
                    </CardTitle>
                    {!d.current && (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        onClick={() => void logout(d.id)}
                        aria-label={`${deviceName(d.userAgent)} 로그아웃`}
                      >
                        로그아웃
                      </Button>
                    )}
                  </CardHeader>
                  <CardContent className="text-sm text-muted-foreground">
                    로그인 {time.format(new Date(d.loginAt))} · 최근 사용{" "}
                    {time.format(new Date(d.lastSeenAt))}
                  </CardContent>
                </Card>
              </li>
            ))}
          </ul>
        )}
        {others.length > 0 && (
          <Button
            variant="destructive"
            className="self-start"
            disabled={busy}
            onClick={() => void logout()}
          >
            다른 기기 모두 로그아웃
          </Button>
        )}
      </main>
    </div>
  );
}
