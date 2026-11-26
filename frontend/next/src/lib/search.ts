/**
 * 상품 검색 조건 (홈 화면 검색 폼 ↔ URL ↔ search-service, ADR 0010).
 * URL 의 값은 사용자가 고칠 수 있어 여기서 걸러낸다: 잘못된 숫자·정렬은 없는 것으로 본다(서버도 다시 검사한다)
 */
export const SORTS = {
  relevance: "관련도순",
  "price-asc": "낮은 가격순",
  "price-desc": "높은 가격순",
  newest: "최신순",
} as const;

export type SearchSort = keyof typeof SORTS;

export type SearchCriteria = {
  q?: string;
  minPrice?: number;
  maxPrice?: number;
  sort: SearchSort;
};

type RawParams = Record<string, string | string[] | undefined>;

const MAX_KEYWORD = 100;

function first(value: string | string[] | undefined): string | undefined {
  const v = Array.isArray(value) ? value[0] : value;
  const trimmed = v?.trim();
  return trimmed ? trimmed : undefined;
}

function price(value: string | string[] | undefined): number | undefined {
  const v = first(value);
  if (v === undefined || !/^\d{1,9}$/.test(v)) {
    return undefined;
  }
  return Number(v);
}

/** 검색 조건이 하나도 없으면 null (그때는 전체 목록) */
export function parseSearch(raw: RawParams): SearchCriteria | null {
  const q = first(raw.q)?.slice(0, MAX_KEYWORD);
  let minPrice = price(raw.minPrice);
  let maxPrice = price(raw.maxPrice);
  if (minPrice !== undefined && maxPrice !== undefined && minPrice > maxPrice) {
    [minPrice, maxPrice] = [maxPrice, minPrice];
  }
  const s = first(raw.sort);
  const sort: SearchSort = s !== undefined && s in SORTS ? (s as SearchSort) : "relevance";
  if (q === undefined && minPrice === undefined && maxPrice === undefined && sort === "relevance") {
    return null;
  }
  return { q, minPrice, maxPrice, sort };
}

/** search-service 쿼리 문자열 (판매 중인 상품만) */
export function searchQuery(c: SearchCriteria, size: number): string {
  const params = new URLSearchParams();
  if (c.q) params.set("q", c.q);
  if (c.minPrice !== undefined) params.set("minPrice", String(c.minPrice));
  if (c.maxPrice !== undefined) params.set("maxPrice", String(c.maxPrice));
  params.set("sort", c.sort);
  params.set("size", String(size));
  return params.toString();
}
