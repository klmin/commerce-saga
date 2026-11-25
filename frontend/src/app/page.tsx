import Link from "next/link";
import { Suspense } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiError } from "@/lib/api/client";
import { listProducts } from "@/lib/api/products";
import { searchProducts } from "@/lib/api/search";
import type { Product } from "@/lib/products";
import { parseSearch, type SearchCriteria } from "@/lib/search";

import { addToCartAction } from "./cart/cart-actions";
import { won } from "@/lib/products";

import { ListSkeleton, ValueSkeleton } from "./loading-skeletons";
import { ProductSearchForm } from "./product-search-form";
import { pointBalanceOrUndefined, stocksByProduct } from "./shop-data";

// 가입 직후에는 포인트 계정이 아직 없을 수 있다: auth.events 로 몇 초 안에 생긴다 (시나리오 13)
async function PointBalance() {
  const balance = await pointBalanceOrUndefined();
  if (balance === undefined) {
    return (
      <p className="text-sm text-muted-foreground">
        포인트 계정을 만드는 중입니다. 잠시 뒤 새로고침해 주세요.
      </p>
    );
  }
  return <p className="text-2xl font-semibold">{balance.toLocaleString("ko-KR")}P</p>;
}

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

// searchParams 는 요청 시점 데이터라 Suspense 안에서 await 한다 (Cache Components)
async function ProductSection({ searchParams }: Pick<PageProps<"/">, "searchParams">) {
  const criteria = parseSearch(await searchParams);
  const { products, total, notice } = await loadListing(criteria);
  return (
    <>
      <ProductSearchForm criteria={criteria} />
      {notice && (
        <p role="alert" className="text-sm text-muted-foreground">
          {notice}
        </p>
      )}
      {total !== undefined && (
        <p className="text-sm text-muted-foreground">
          검색 결과 {total.toLocaleString("ko-KR")}개
          {total > products.length && ` 중 ${products.length}개`}. 검색은 몇 초 늦게 반영될 수
          있습니다.
        </p>
      )}
      {products.length === 0 && total !== undefined && !notice && (
        <p className="text-sm">조건에 맞는 상품이 없습니다.</p>
      )}
      <ProductList products={products} />
    </>
  );
}

async function ProductList({ products }: { products: Product[] }) {
  const stocks = await stocksByProduct(products);
  return (
    <ul aria-label="상품 목록" className="flex flex-col divide-y">
      {products.map((p) => {
        const stock = stocks[p.id];
        return (
          <li key={p.id} className="flex items-center gap-4 py-3">
            <div className="flex flex-1 flex-col">
              <span className="font-medium">{p.name}</span>
              <span className="text-sm text-muted-foreground">
                {won(p.price)} · {stock === undefined ? "재고 확인 불가" : `재고 ${stock}개`}
                {p.description && ` · ${p.description}`}
              </span>
            </div>
            <form action={addToCartAction}>
              <input type="hidden" name="productId" value={p.id} />
              <Button
                type="submit"
                size="sm"
                variant="outline"
                aria-label={`${p.name} 장바구니 담기`}
              >
                담기
              </Button>
            </form>
            <Button size="sm" variant={stock === 0 ? "outline" : "default"} asChild>
              <Link href={`/orders/new?productId=${p.id}`}>주문</Link>
            </Button>
          </li>
        );
      })}
    </ul>
  );
}

export default function Home({ searchParams }: PageProps<"/">) {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Card>
        <CardHeader>
          <CardTitle>내 포인트</CardTitle>
        </CardHeader>
        <CardContent>
          <Suspense fallback={<ValueSkeleton />}>
            <PointBalance />
          </Suspense>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>상품</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          <p className="text-sm text-muted-foreground">
            가격은 주문할 때 다시 확인합니다. 그사이 가격이 바뀌면 바뀐 가격을 보여 드리고 확인을
            받습니다. 품절 상품도 주문할 수 있습니다. 재고 예약 단계에서 실패하고 주문이 취소되는
            과정을 주문 상세에서 볼 수 있습니다.
          </p>
          <Suspense fallback={<ListSkeleton rows={4} />}>
            <ProductSection searchParams={searchParams} />
          </Suspense>
        </CardContent>
      </Card>
    </main>
  );
}
