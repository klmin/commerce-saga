import { Button } from "@commerce/ui/button";
import { Input } from "@commerce/ui/input";
import { Label } from "@commerce/ui/label";
import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";

import { myPointBalance } from "@/api/points";
import { listProducts, searchProducts, stocksByProduct } from "@/api/products";
import { LoadError, Page, Section } from "@commerce/ui/page";
import { ListSkeleton, ValueSkeleton } from "@commerce/ui/skeletons";
import { ApiError } from "@commerce/bff/api";
import { ProductImage } from "@/components/product-image";
import { useAddToCart } from "@/hooks/use-add-to-cart";
import { useCategories } from "@/hooks/use-categories";
import { type Product, won } from "@/lib/products";
import { parseSearch, type SearchCriteria, SORTS } from "@/lib/search";
import { stockLabel, stockState } from "@/lib/stock";

type Listing = { products: Product[]; total?: number; notice?: string };

/**
 * 검색 조건이 있으면 search-service(ADR 0010), 없으면 product-service 목록. 검색 저장소가 멈추면(503) 전체 목록으로
 * 대신 보여 준다: 검색은 보조 기능이라 상품을 볼 수 있어야 한다
 */
async function loadListing(criteria: SearchCriteria | null): Promise<Listing> {
  if (!criteria) {
    return { products: await listProducts() };
  }
  try {
    const result = await searchProducts(criteria);
    return { products: result.items, total: result.total };
  } catch (e) {
    if (e instanceof ApiError && e.status === 503) {
      return {
        products: await listProducts(),
        notice: "검색을 잠시 사용할 수 없어 전체 상품을 보여 드립니다.",
      };
    }
    if (e instanceof ApiError && e.status === 400) {
      return { products: [], total: 0, notice: e.message };
    }
    throw e;
  }
}

/** 가입 직후에는 포인트 계정이 아직 없을 수 있다: auth.events 로 몇 초 안에 생긴다 (시나리오 13) */
export function PointBalance() {
  const balance = useQuery({ queryKey: ["points", "balance"], queryFn: myPointBalance });
  if (balance.isPending) {
    return <ValueSkeleton />;
  }
  if (balance.isError) {
    return <LoadError error={balance.error} retry={() => balance.refetch()} />;
  }
  if (balance.data === null) {
    return (
      <p className="text-sm text-muted-foreground">
        포인트 계정을 만드는 중입니다. 잠시 뒤 새로고침해 주세요.
      </p>
    );
  }
  return <p className="text-2xl font-semibold">{balance.data.toLocaleString("ko-KR")}P</p>;
}

/**
 * 검색 조건 폼 (가격 범위·정렬). 검색어는 헤더 검색창, 카테고리는 헤더 메뉴가 정하고 여기서는 그대로 넘긴다.
 * 조건을 URL 에 남겨 새로고침·공유가 된다. 조건마다 key 를 달리해 입력을 다시 채운다
 */
function ProductSearchForm({ criteria }: { criteria: SearchCriteria | null }) {
  const [, setParams] = useSearchParams();
  return (
    <form
      key={JSON.stringify(criteria)}
      role="search"
      aria-label="검색 조건"
      className="flex flex-wrap items-end gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        const data = new FormData(e.currentTarget);
        const next = new URLSearchParams();
        for (const name of ["q", "category", "minPrice", "maxPrice", "sort"]) {
          const value = String(data.get(name) ?? "").trim();
          if (value) {
            next.set(name, value);
          }
        }
        setParams(next);
      }}
    >
      <input type="hidden" name="q" value={criteria?.q ?? ""} />
      <input type="hidden" name="category" value={criteria?.category ?? ""} />
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
      <Button type="submit">적용</Button>
      {criteria && (
        <Button variant="ghost" asChild>
          <Link to="/">전체 보기</Link>
        </Button>
      )}
    </form>
  );
}

function ProductList({ products }: { products: Product[] }) {
  const { add, adding } = useAddToCart();
  const categories = useCategories();
  const stocks = useQuery({
    queryKey: ["stocks", products.map((p) => p.id)],
    queryFn: () => stocksByProduct(products),
  });

  return (
    <ul aria-label="상품 목록" className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
      {products.map((p) => {
        const state = stockState(stocks.data?.[p.id], stocks.isPending);
        const detail = `/products/${encodeURIComponent(p.id)}`;
        const category = categories.data?.find((c) => c.code === p.categoryCode)?.name;
        return (
          <li
            key={p.id}
            className="flex flex-col overflow-hidden rounded-lg border bg-card text-card-foreground shadow-xs"
          >
            {/* 이미지도 상세로 가지만 이름 링크와 같은 곳이라 탭 순서·읽기에서는 뺀다 */}
            <Link to={detail} tabIndex={-1} aria-hidden="true" className="relative block">
              <ProductImage product={p} decorative className="w-full rounded-none" />
              {state.kind === "soldOut" && (
                <span className="absolute top-2 left-2 rounded bg-foreground/80 px-2 py-0.5 text-xs font-medium text-background">
                  품절
                </span>
              )}
            </Link>
            <div className="flex flex-1 flex-col gap-1 p-3">
              {category && <span className="text-xs text-muted-foreground">{category}</span>}
              <h3 className="line-clamp-2 font-medium">
                <Link to={detail} className="hover:underline">
                  {p.name}
                </Link>
              </h3>
              <p className="font-semibold">{won(p.price)}</p>
              <p
                className={`text-xs ${
                  state.kind === "soldOut" || state.kind === "low"
                    ? "text-destructive"
                    : "text-muted-foreground"
                }`}
              >
                {stockLabel(state)}
              </p>
              <div className="mt-auto flex gap-2 pt-2">
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="flex-1"
                  aria-label={`${p.name} 장바구니 담기`}
                  disabled={adding !== undefined}
                  onClick={() => add(p.id)}
                >
                  담기
                </Button>
                <Button
                  size="sm"
                  variant={state.kind === "soldOut" ? "outline" : "default"}
                  className="flex-1"
                  asChild
                >
                  <Link to={`/orders/new?productId=${encodeURIComponent(p.id)}`}>주문</Link>
                </Button>
              </div>
            </div>
          </li>
        );
      })}
    </ul>
  );
}

function ProductSection() {
  const [params] = useSearchParams();
  const criteria = parseSearch(Object.fromEntries(params));
  const listing = useQuery({
    queryKey: ["listing", criteria],
    queryFn: () => loadListing(criteria),
  });
  return (
    <>
      <ProductSearchForm criteria={criteria} />
      {listing.isPending ? (
        <ListSkeleton rows={4} />
      ) : listing.isError ? (
        <LoadError error={listing.error} retry={() => listing.refetch()} />
      ) : (
        <>
          {listing.data.notice && (
            <p role="alert" className="text-sm text-muted-foreground">
              {listing.data.notice}
            </p>
          )}
          {listing.data.total !== undefined && (
            <p className="text-sm text-muted-foreground">
              검색 결과 {listing.data.total.toLocaleString("ko-KR")}개
              {listing.data.total > listing.data.products.length &&
                ` 중 ${listing.data.products.length}개`}
              . 검색은 몇 초 늦게 반영될 수 있습니다.
            </p>
          )}
          {listing.data.products.length === 0 &&
            listing.data.total !== undefined &&
            !listing.data.notice && <p className="text-sm">조건에 맞는 상품이 없습니다.</p>}
          <ProductList products={listing.data.products} />
        </>
      )}
    </>
  );
}

/** 목록 제목: 고른 카테고리 이름(모르는 코드는 "상품")과 검색어 */
function useListingTitle(): string {
  const [params] = useSearchParams();
  const categories = useCategories();
  const criteria = parseSearch(Object.fromEntries(params));
  const category = criteria?.category
    ? categories.data?.find((c) => c.code === criteria.category)?.name
    : undefined;
  const base = category ?? "상품";
  return criteria?.q ? `${base} · "${criteria.q}" 검색` : base;
}

export function HomePage() {
  const title = useListingTitle();
  return (
    <Page wide>
      <Section title="내 포인트">
        <PointBalance />
      </Section>
      {/* 고른 카테고리·검색어가 바뀌는 목록 제목 (화면 읽기 프로그램이 제목으로 찾게) */}
      <Section title={<h2>{title}</h2>}>
        <p className="text-sm text-muted-foreground">
          가격은 주문할 때 다시 확인합니다. 그사이 가격이 바뀌면 바뀐 가격을 보여 드리고 확인을
          받습니다. 품절 상품도 주문할 수 있습니다. 재고 예약 단계에서 실패하고 주문이 취소되는
          과정을 주문 상세에서 볼 수 있습니다.
        </p>
        <ProductSection />
      </Section>
    </Page>
  );
}
