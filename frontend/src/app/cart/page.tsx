import { randomUUID } from "node:crypto";

import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { Suspense } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { getCart } from "@/lib/api/cart";
import { MAX_QUANTITY } from "@/lib/orders/order-input";
import { type Product, productName, won } from "@/lib/products";

import { ListSkeleton } from "../loading-skeletons";
import { OrderForm } from "../orders/new/order-form";
import { pointBalanceOrUndefined, stocksByProduct } from "../shop-data";
import { removeFromCartAction, updateCartQuantityAction } from "./cart-actions";

export const metadata: Metadata = { title: "장바구니 · commerce-saga" };

const ERRORS: Record<string, string> = {
  unavailable: "판매 중지됐거나 없는 상품이라 담을 수 없습니다.",
  down: "상품 정보를 확인할 수 없어 지금은 담을 수 없습니다. 잠시 뒤 다시 시도해 주세요.",
  quantity: `수량은 0~${MAX_QUANTITY} 사이 정수로 입력해 주세요. 0 이면 뺍니다.`,
  failed: "장바구니를 바꾸지 못했습니다. 다시 시도해 주세요.",
};

async function CartContent({ searchParams }: Pick<PageProps<"/cart">, "searchParams">) {
  await connection();
  const raw = (await searchParams).error;
  const error = typeof raw === "string" ? ERRORS[raw] : undefined;
  const cart = await getCart();

  if (cart.items.length === 0) {
    return (
      <div className="flex flex-col items-start gap-3">
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        <p className="text-sm text-muted-foreground">장바구니가 비어 있습니다.</p>
        <Button size="sm" asChild>
          <Link href="/">상품 보러 가기</Link>
        </Button>
      </div>
    );
  }

  // 주문할 수 있는 줄만 주문 폼으로. 가격은 지금 가격(모르면 담을 때 가격). 주문 때 서버가 다시 확인한다
  const orderable: Product[] = cart.items
    .filter((i) => i.onSale !== false)
    .map((i) => ({
      id: i.productId,
      name: i.name ?? productName(i.productId),
      price: i.currentPrice ?? i.addedPrice,
      description: "",
      onSale: true,
    }));
  const [stocks, pointBalance] = await Promise.all([
    stocksByProduct(orderable),
    pointBalanceOrUndefined(),
  ]);

  return (
    <div className="flex flex-col gap-6">
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      {!cart.pricesAvailable && (
        <p role="status" className="text-sm text-muted-foreground">
          지금 가격을 확인할 수 없어 담을 때 가격으로 보여 드립니다. 주문할 때 다시 확인합니다.
        </p>
      )}
      <ul className="flex flex-col divide-y rounded-md border" aria-label="장바구니 상품">
        {cart.items.map((i) => (
          <li key={i.productId} className="flex flex-wrap items-center gap-4 p-3">
            <div className="flex flex-1 flex-col">
              <span className="font-medium">{i.name ?? productName(i.productId)}</span>
              <span className="text-sm text-muted-foreground">
                {i.onSale === false ? (
                  <span className="text-destructive">판매 중지 (주문할 수 없음)</span>
                ) : i.priceChanged && i.currentPrice !== null ? (
                  <span data-testid={`cart-price-changed-${i.productId}`}>
                    담을 때 <s>{won(i.addedPrice)}</s> → 지금{" "}
                    <span className="font-medium text-destructive">{won(i.currentPrice)}</span>
                  </span>
                ) : (
                  won(i.currentPrice ?? i.addedPrice)
                )}
              </span>
            </div>
            <form action={updateCartQuantityAction} className="flex items-center gap-2">
              <input type="hidden" name="productId" value={i.productId} />
              <Input
                name="quantity"
                aria-label={`${i.name ?? productName(i.productId)} 수량`}
                inputMode="numeric"
                pattern="\d*"
                defaultValue={i.quantity}
                maxLength={String(MAX_QUANTITY).length}
                className="w-20 text-right"
              />
              <Button type="submit" variant="outline" size="sm">
                변경
              </Button>
            </form>
            <form action={removeFromCartAction}>
              <input type="hidden" name="productId" value={i.productId} />
              <Button type="submit" variant="ghost" size="sm">
                빼기
              </Button>
            </form>
          </li>
        ))}
      </ul>

      {orderable.length > 0 && (
        <section className="flex flex-col gap-3">
          <h2 className="font-medium">장바구니 주문</h2>
          <p className="text-sm text-muted-foreground">
            주문하면 주문한 상품이 장바구니에서 빠집니다. 수량을 0 으로 두면 그 상품은 주문하지 않고
            남겨 둡니다.
          </p>
          {/* 폼 상태(수량)는 처음 마운트 때만 initialQuantities 를 읽는다. 장바구니가 바뀌면(수량 변경·빼기)
              key 가 바뀌어 새 수량으로 다시 마운트한다 */}
          <OrderForm
            key={cart.items.map((i) => `${i.productId}:${i.quantity}`).join(",")}
            idempotencyKey={randomUUID()}
            products={orderable}
            stocks={stocks}
            pointBalance={pointBalance}
            initialQuantities={Object.fromEntries(
              cart.items.map((i) => [i.productId, i.quantity] as const),
            )}
            fromCart
          />
        </section>
      )}
    </div>
  );
}

export default function CartPage({ searchParams }: PageProps<"/cart">) {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Card>
        <CardHeader>
          <CardTitle>장바구니</CardTitle>
        </CardHeader>
        <CardContent>
          <Suspense fallback={<ListSkeleton />}>
            <CartContent searchParams={searchParams} />
          </Suspense>
        </CardContent>
      </Card>
    </main>
  );
}
