import Link from "next/link";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { SORTS, type SearchCriteria } from "@/lib/search";

/**
 * 상품 검색 폼. 자바스크립트 없이 GET 으로 홈(/?q=...)을 다시 그린다 → 검색 조건이 URL 에 남아 새로고침·공유가 된다.
 * 서버 컴포넌트라 상태가 없고, 기본값은 지금 URL 의 조건이다
 */
export function ProductSearchForm({ criteria }: { criteria: SearchCriteria | null }) {
  return (
    <form action="/" method="get" role="search" className="flex flex-wrap items-end gap-2">
      <div className="flex min-w-40 flex-1 flex-col gap-1">
        <Label htmlFor="search-q">검색어</Label>
        <Input
          id="search-q"
          name="q"
          type="search"
          maxLength={100}
          placeholder="상품 이름·설명 (예: 마우)"
          defaultValue={criteria?.q ?? ""}
        />
      </div>
      <div className="flex w-28 flex-col gap-1">
        <Label htmlFor="search-min">최소 가격</Label>
        <Input
          id="search-min"
          name="minPrice"
          inputMode="numeric"
          pattern="[0-9]*"
          defaultValue={criteria?.minPrice ?? ""}
        />
      </div>
      <div className="flex w-28 flex-col gap-1">
        <Label htmlFor="search-max">최대 가격</Label>
        <Input
          id="search-max"
          name="maxPrice"
          inputMode="numeric"
          pattern="[0-9]*"
          defaultValue={criteria?.maxPrice ?? ""}
        />
      </div>
      <div className="flex flex-col gap-1">
        <Label htmlFor="search-sort">정렬</Label>
        <select
          id="search-sort"
          name="sort"
          defaultValue={criteria?.sort ?? "relevance"}
          className="h-9 rounded-md border border-input bg-transparent px-2 text-sm"
        >
          {Object.entries(SORTS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </div>
      <Button type="submit">검색</Button>
      {criteria && (
        <Button variant="ghost" asChild>
          <Link href="/">전체 보기</Link>
        </Button>
      )}
    </form>
  );
}
