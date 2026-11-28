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
  /** 카테고리 코드 (ADR 0021, 헤더 카테고리 메뉴) */
  category?: string;
  minPrice?: number;
  maxPrice?: number;
  sort: SearchSort;
};

type RawParams = Record<string, string | string[] | undefined>;

const MAX_KEYWORD = 100;
/** product-service CategoryCode 모양. 다른 값은 없는 것으로 본다 (서버도 400) */
const CATEGORY_CODE = /^[a-z][a-z0-9-]{0,29}$/;

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
  const c = first(raw.category);
  const category = c !== undefined && CATEGORY_CODE.test(c) ? c : undefined;
  let minPrice = price(raw.minPrice);
  let maxPrice = price(raw.maxPrice);
  if (minPrice !== undefined && maxPrice !== undefined && minPrice > maxPrice) {
    [minPrice, maxPrice] = [maxPrice, minPrice];
  }
  const s = first(raw.sort);
  const sort: SearchSort = s !== undefined && s in SORTS ? (s as SearchSort) : "relevance";
  if (
    q === undefined &&
    category === undefined &&
    minPrice === undefined &&
    maxPrice === undefined &&
    sort === "relevance"
  ) {
    return null;
  }
  return { q, category, minPrice, maxPrice, sort };
}

/** search-service 쿼리 문자열 (판매 중인 상품만) */
export function searchQuery(c: SearchCriteria, size: number): string {
  const params = new URLSearchParams();
  if (c.q) params.set("q", c.q);
  if (c.category) params.set("category", c.category);
  if (c.minPrice !== undefined) params.set("minPrice", String(c.minPrice));
  if (c.maxPrice !== undefined) params.set("maxPrice", String(c.maxPrice));
  params.set("sort", c.sort);
  params.set("size", String(size));
  return params.toString();
}

/**
 * 헤더 검색창 → 홈 URL. 검색어만 새로 정하고 지금 고른 카테고리는 유지한다(카테고리 안에서 검색).
 * 가격 범위·정렬은 새 검색이라 비운다. 검색어가 비면 카테고리만(없으면 전체)
 */
export function headerSearchUrl(q: string, current: URLSearchParams): string {
  const next = new URLSearchParams();
  const keyword = q.trim().slice(0, MAX_KEYWORD);
  if (keyword) next.set("q", keyword);
  const category = first(current.get("category") ?? undefined);
  if (category !== undefined && CATEGORY_CODE.test(category)) next.set("category", category);
  const query = next.toString();
  return query ? `/?${query}` : "/";
}

/** 카테고리 메뉴 → 홈 URL. 검색어는 유지(검색 결과를 카테고리로 좁힘), 가격·정렬은 비운다. code 가 없으면 전체 */
export function categoryUrl(code: string | undefined, current: URLSearchParams): string {
  const next = new URLSearchParams();
  const q = first(current.get("q") ?? undefined)?.slice(0, MAX_KEYWORD);
  if (q) next.set("q", q);
  if (code) next.set("category", code);
  const query = next.toString();
  return query ? `/?${query}` : "/";
}
