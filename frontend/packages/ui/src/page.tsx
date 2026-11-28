import { Card, CardContent, CardHeader, CardTitle } from "./card";
import type { ReactNode } from "react";

/**
 * 화면 한 장의 틀 (가운데 정렬된 본문). wide 면 목록·카드 그리드용으로 넓게(헤더와 같은 폭).
 * 앱 틀(헤더 등)이 main 을 따로 두지 않으므로 화면마다 main 은 이것 하나
 */
export function Page({ children, wide = false }: { children: ReactNode; wide?: boolean }) {
  return (
    <main
      className={`mx-auto flex w-full flex-col gap-6 px-4 py-8 ${wide ? "max-w-6xl" : "max-w-3xl"}`}
    >
      {children}
    </main>
  );
}

/** 제목 있는 카드. action 은 제목 오른쪽 */
export function Section({
  title,
  action,
  children,
}: {
  title: ReactNode;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <Card>
      <CardHeader
        className={action ? "flex flex-row items-center justify-between gap-4" : undefined}
      >
        <CardTitle>{title}</CardTitle>
        {action}
      </CardHeader>
      <CardContent className="flex flex-col gap-2">{children}</CardContent>
    </Card>
  );
}

/** 불러오기 실패 (401 은 api() 가 로그인으로 보내 여기 오지 않는다) */
export function LoadError({ error, retry }: { error: unknown; retry?: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-start gap-2 text-sm">
      <p className="text-destructive">
        {error instanceof Error ? error.message : "불러오지 못했습니다."}
      </p>
      {retry && (
        <button type="button" className="underline underline-offset-4" onClick={retry}>
          다시 시도
        </button>
      )}
    </div>
  );
}
