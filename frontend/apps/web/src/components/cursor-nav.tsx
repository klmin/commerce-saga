import { Button } from "@commerce/ui/button";
import { Link } from "react-router";

/** 커서 목록의 "처음으로"·"더 보기". 한 쪽이 꽉 찼을 때만 다음 쪽이 있을 수 있다 */
export function CursorNav({
  basePath,
  isFirstPage,
  nextCursor,
  moreLabel,
}: {
  basePath: string;
  isFirstPage: boolean;
  /** 다음 쪽이 없으면 undefined */
  nextCursor: string | undefined;
  moreLabel: string;
}) {
  if (isFirstPage && !nextCursor) {
    return null;
  }
  return (
    <nav className="flex gap-2">
      {!isFirstPage && (
        <Button variant="outline" size="sm" asChild>
          <Link to={basePath}>처음으로</Link>
        </Button>
      )}
      {nextCursor && (
        <Button variant="outline" size="sm" asChild>
          <Link to={`${basePath}?before=${encodeURIComponent(nextCursor)}`}>{moreLabel}</Link>
        </Button>
      )}
    </nav>
  );
}
