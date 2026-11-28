import { Button } from "@commerce/ui/button";
import { Input } from "@commerce/ui/input";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";

import { type Cart, getCart, putCartItem, removeCartItem } from "@/api/cart";
import { myPointBalance } from "@/api/points";
import { productsByIds, stocksByProduct } from "@/api/products";
import { ProductImage } from "@/components/product-image";
import { OrderForm } from "@/components/order-form";
import { LoadError, Page, Section } from "@commerce/ui/page";
import { ListSkeleton } from "@commerce/ui/skeletons";
import { CART_ERRORS, cartErrorCode } from "@/lib/cart-errors";
import { MAX_QUANTITY } from "@/lib/orders/order-input";
import { type Product, type ProductId, productName, won } from "@/lib/products";

function CartContent({ cart }: { cart: Cart }) {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);
  const error = CART_ERRORS[params.get("error") ?? ""];

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
  const stocks = useQuery({
    queryKey: ["stocks", orderable.map((p) => p.id)],
    queryFn: () => stocksByProduct(orderable),
    enabled: orderable.length > 0,
  });
  const balance = useQuery({ queryKey: ["points", "balance"], queryFn: myPointBalance });
  // 이미지는 상품에서 읽는다 (장바구니 응답에는 없다). 못 읽으면 자리 표시
  const ids = cart.items.map((i) => i.productId);
  const details = useQuery({
    queryKey: ["products-by-ids", ids],
    queryFn: () => productsByIds(ids),
    enabled: ids.length > 0,
  });

  /** 바꾼 장바구니를 그대로 보여 주고 오류 표시는 지운다. 실패하면 이유를 ?error= 로 */
  async function change(work: () => Promise<Cart>) {
    setBusy(true);
    try {
      queryClient.setQueryData(["cart"], await work());
      navigate("/cart", { replace: true });
    } catch (e) {
      navigate(`/cart?error=${cartErrorCode(e)}`, { replace: true });
    } finally {
      setBusy(false);
    }
  }

  function onQuantity(productId: ProductId, event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const quantity = Number(new FormData(event.currentTarget).get("quantity"));
    if (!Number.isInteger(quantity) || quantity < 0 || quantity > MAX_QUANTITY) {
      navigate("/cart?error=quantity", { replace: true });
      return;
    }
    // 0 이면 뺀다
    void change(() =>
      quantity === 0 ? removeCartItem(productId) : putCartItem(productId, quantity),
    );
  }

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
          <Link to="/">상품 보러 가기</Link>
        </Button>
      </div>
    );
  }

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
            <ProductImage
              product={{
                name: i.name ?? productName(i.productId),
                imagePath: details.data?.[i.productId]?.imagePath,
              }}
              decorative
              className="w-16 shrink-0"
            />
            <div className="flex min-w-32 flex-1 flex-col">
              <Link
                to={`/products/${encodeURIComponent(i.productId)}`}
                className="font-medium hover:underline"
              >
                {i.name ?? productName(i.productId)}
              </Link>
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
            <form
              // 수량이 바뀌면 입력을 새 값으로 다시 그린다
              key={i.quantity}
              onSubmit={(e) => onQuantity(i.productId, e)}
              className="flex items-center gap-2"
            >
              <Input
                name="quantity"
                aria-label={`${i.name ?? productName(i.productId)} 수량`}
                inputMode="numeric"
                pattern="\d*"
                defaultValue={i.quantity}
                maxLength={String(MAX_QUANTITY).length}
                className="w-20 text-right"
              />
              <Button type="submit" variant="outline" size="sm" disabled={busy}>
                변경
              </Button>
            </form>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={busy}
              onClick={() => change(() => removeCartItem(i.productId))}
            >
              빼기
            </Button>
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
          {/* 폼 상태(수량)는 처음 마운트 때만 initialQuantities 를 읽는다. 장바구니가 바뀌면 key 가 바뀌어 다시 마운트한다 */}
          <OrderForm
            key={cart.items.map((i) => `${i.productId}:${i.quantity}`).join(",")}
            products={orderable}
            stocks={stocks.data ?? {}}
            pointBalance={balance.data}
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

export function CartPage() {
  const cart = useQuery({ queryKey: ["cart"], queryFn: getCart });
  return (
    <Page>
      <Section title="장바구니">
        {cart.isPending ? (
          <ListSkeleton />
        ) : cart.isError ? (
          <LoadError error={cart.error} retry={() => cart.refetch()} />
        ) : (
          <CartContent cart={cart.data} />
        )}
      </Section>
    </Page>
  );
}
