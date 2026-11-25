import Link from "next/link";
import { Suspense } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { listProducts } from "@/lib/api/products";
import { won } from "@/lib/products";

import { pointBalanceOrUndefined, stocksByProduct } from "./shop-data";

const loading = (
  <p role="status" className="text-sm text-muted-foreground">
    불러오는 중...
  </p>
);

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

async function ProductList() {
  const products = await listProducts();
  const stocks = await stocksByProduct(products);
  return (
    <ul className="flex flex-col divide-y">
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
            <Button size="sm" variant={stock === 0 ? "outline" : "default"} asChild>
              <Link href={`/orders/new?productId=${p.id}`}>주문</Link>
            </Button>
          </li>
        );
      })}
    </ul>
  );
}

export default function Home() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Card>
        <CardHeader>
          <CardTitle>내 포인트</CardTitle>
        </CardHeader>
        <CardContent>
          <Suspense fallback={loading}>
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
          <Suspense fallback={loading}>
            <ProductList />
          </Suspense>
        </CardContent>
      </Card>
    </main>
  );
}
