import { Button } from "@commerce/ui/button";
import { isRouteErrorResponse, Link, useRouteError } from "react-router";

import { Page } from "@/components/page";

/** 없는 화면·없는(또는 남의) 주문. embedded 면 다른 화면 안에 끼워 그린다 */
export function NotFoundPage({ embedded = false }: { embedded?: boolean }) {
  const body = (
    <div className="flex flex-col gap-4 py-4">
      <h1 className="text-xl font-semibold">페이지를 찾을 수 없습니다</h1>
      <Button variant="outline" asChild className="self-start">
        <Link to="/">처음으로</Link>
      </Button>
    </div>
  );
  return embedded ? body : <Page>{body}</Page>;
}

/** 화면을 그리다 난 오류 (라우터 errorElement) */
export function ErrorPage() {
  const error = useRouteError();
  if (isRouteErrorResponse(error) && error.status === 404) {
    return <NotFoundPage />;
  }
  console.error(error);
  return (
    <Page>
      <h1 className="text-xl font-semibold">문제가 발생했습니다</h1>
      <p className="text-sm text-muted-foreground">잠시 후 다시 시도해 주세요.</p>
      <Button type="button" onClick={() => window.location.reload()} className="self-start">
        다시 시도
      </Button>
    </Page>
  );
}
