import { randomUUID } from "node:crypto";

import type { Metadata } from "next";
import { connection } from "next/server";
import { Suspense } from "react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { listProducts } from "@/lib/api/products";

import { FormSkeleton } from "../../loading-skeletons";
import { pointBalanceOrUndefined, stocksByProduct } from "../../shop-data";
import { OrderForm } from "./order-form";

export const metadata: Metadata = { title: "주문하기 · commerce-saga" };

// 재고·포인트·searchParams 는 요청 시점 데이터라 Suspense 안에서 읽는다 (Cache Components)
async function OrderFormWithData({ searchParams }: Pick<PageProps<"/orders/new">, "searchParams">) {
  await connection();
  const raw = (await searchParams).productId;
  const productId = Number(typeof raw === "string" ? raw : NaN);
  const products = await listProducts();
  const [stocks, pointBalance] = await Promise.all([
    stocksByProduct(products),
    pointBalanceOrUndefined(),
  ]);
  return (
    <OrderForm
      // 폼을 열 때마다 새 키. 이 폼에서 다시 누르는 것은 같은 주문으로 처리된다
      idempotencyKey={randomUUID()}
      products={products}
      stocks={stocks}
      pointBalance={pointBalance}
      initialProductId={products.find((p) => p.id === productId)?.id}
    />
  );
}

export default function NewOrderPage({ searchParams }: PageProps<"/orders/new">) {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Card>
        <CardHeader>
          <CardTitle>주문하기</CardTitle>
        </CardHeader>
        <CardContent>
          <Suspense fallback={<FormSkeleton fields={3} />}>
            <OrderFormWithData searchParams={searchParams} />
          </Suspense>
        </CardContent>
      </Card>
    </main>
  );
}
