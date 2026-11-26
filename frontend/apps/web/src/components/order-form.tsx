import { Button } from "@commerce/ui/button";
import { Input } from "@commerce/ui/input";
import { Label } from "@commerce/ui/label";
import { useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useId, useState } from "react";
import { useNavigate } from "react-router";

import { placeOrder } from "@/api/orders";
import { ApiError } from "@/lib/api";
import { MAX_QUANTITY, orderTotal, toOrderInput } from "@/lib/orders/order-input";
import { type Product, won } from "@/lib/products";
import { uuid } from "@/lib/uuid";

type Props = {
  /** 판매 중인 상품. 가격은 화면을 연 시점 값 */
  products: Product[];
  /** 상품 id → 지금 주문 가능한 재고 (모르면 없음) */
  stocks: Record<number, number | undefined>;
  /** 내 포인트 잔액 (계정이 아직 없으면 null) */
  pointBalance: number | null | undefined;
  /** 상품 목록에서 고르고 온 상품 */
  initialProductId?: number;
  /** 처음 수량 (장바구니 주문) */
  initialQuantities?: Record<number, number>;
  /** 장바구니 주문이면 주문한 상품이 장바구니에서 빠진다 */
  fromCart?: boolean;
};

function toNumber(text: string): number {
  return /^\d+$/.test(text) ? Number(text) : 0;
}

function pricesOf(error: ApiError): Record<number, number> {
  const prices = error.problem?.currentPrices;
  if (!Array.isArray(prices)) {
    return {};
  }
  return Object.fromEntries(prices.map((p) => [p.productId, p.price]));
}

/**
 * 주문 폼. 폼을 열 때 정한 Idempotency-Key 로 보낸다: 두 번 누르거나 응답을 못 받고 다시 눌러도 주문은 한 건 (시나리오 10).
 * 보이는 가격을 함께 보내고, 서버 가격과 다르면(409) 바뀐 가격을 보여 준 뒤 다시 누르면 그 가격으로 주문한다 (ADR 0016).
 * 성공하면 주문 상세(Saga 진행)로 간다
 */
export function OrderForm({
  products,
  stocks,
  pointBalance,
  initialProductId,
  initialQuantities,
  fromCart = false,
}: Props) {
  const id = useId();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [idempotencyKey, setIdempotencyKey] = useState(uuid);
  const [currentPrices, setCurrentPrices] = useState<Record<number, number>>({});
  const [error, setError] = useState<string>();
  const [pending, setPending] = useState(false);
  const [quantities, setQuantities] = useState<Record<number, string>>(() =>
    Object.fromEntries(
      products.map((p) => [
        p.id,
        initialQuantities?.[p.id] !== undefined
          ? String(initialQuantities[p.id])
          : p.id === initialProductId
            ? "1"
            : "",
      ]),
    ),
  );
  const [point, setPoint] = useState("");

  // 409 로 받은 지금 가격이 있으면 그 가격으로 보여 주고 보낸다
  const shown = products.map((p) => ({
    ...p,
    price: currentPrices[p.id] ?? p.price,
    previousPrice:
      currentPrices[p.id] !== undefined && currentPrices[p.id] !== p.price ? p.price : undefined,
  }));
  const total = orderTotal(
    Object.fromEntries(shown.map((p) => [p.id, toNumber(quantities[p.id] ?? "")])),
    shown,
  );
  const pointAmount = toNumber(point);
  const toPay = Math.max(0, total - pointAmount);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    const parsed = toOrderInput({ quantities, pointAmount: point }, shown);
    if ("error" in parsed) {
      setError(parsed.error);
      return;
    }
    setPending(true);
    setError(undefined);
    try {
      const order = await placeOrder(
        fromCart ? { ...parsed.input, fromCart } : parsed.input,
        idempotencyKey,
      );
      await queryClient.invalidateQueries();
      navigate(`/orders/${order.orderId}`);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        // 화면 가격과 지금 가격이 다르다. 주문은 만들어지지 않았고 키도 남지 않아 같은 키로 다시 보내면 된다
        setCurrentPrices((prev) => ({ ...prev, ...pricesOf(e) }));
        setError(
          "그사이 상품 가격이 바뀌었습니다. 바뀐 가격을 확인한 뒤 다시 주문하기를 눌러 주세요.",
        );
      } else if (
        e instanceof ApiError &&
        e.status === 422 &&
        e.message.includes("주문할 수 없는 상품")
      ) {
        // 판매 중지·없는 상품. 주문은 만들어지지 않았다
        setError(e.message);
      } else if (e instanceof ApiError && e.status === 422) {
        // 이 키로 이미 다른 내용의 주문이 있다 (앞 요청이 실제로는 접수됐을 수 있다). 새 키로 다시 받는다
        setIdempotencyKey(uuid());
        setError(
          "앞서 보낸 주문과 내용이 달라 접수하지 않았습니다. 내 주문 목록을 확인한 뒤 다시 주문해 주세요.",
        );
      } else if (e instanceof ApiError) {
        setError(e.message);
      } else {
        // 응답을 못 받았다(시간 초과 등). 같은 키로 다시 보내야 주문이 두 번 생기지 않는다
        console.error("place order failed", e);
        setError(
          "주문 결과를 받지 못했습니다. 다시 누르면 같은 주문으로 처리되어 두 번 주문되지 않습니다.",
        );
      }
    } finally {
      setPending(false);
    }
  }

  const errorId = `${id}-error`;
  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-6" aria-describedby={errorId} noValidate>
      <ul className="flex flex-col divide-y rounded-md border">
        {shown.map((p) => {
          const stock = stocks[p.id];
          const soldOut = stock === 0;
          return (
            <li key={p.id} className="flex items-center gap-4 p-3">
              <div className="flex flex-1 flex-col">
                <Label htmlFor={`${id}-qty-${p.id}`}>{p.name}</Label>
                <span className="text-sm text-muted-foreground">
                  {p.previousPrice !== undefined && (
                    <>
                      <s>{won(p.previousPrice)}</s>{" "}
                      <span className="font-medium text-destructive">→ </span>
                    </>
                  )}
                  <span data-testid={`price-${p.id}`}>{won(p.price)}</span> ·{" "}
                  {stock === undefined ? "재고 확인 불가" : `재고 ${stock}개`}
                  {soldOut && " (품절)"}
                </span>
              </div>
              <Input
                id={`${id}-qty-${p.id}`}
                inputMode="numeric"
                pattern="\d*"
                placeholder="0"
                className="w-20 text-right"
                maxLength={String(MAX_QUANTITY).length}
                value={quantities[p.id] ?? ""}
                onChange={(e) => setQuantities((q) => ({ ...q, [p.id]: e.target.value }))}
              />
            </li>
          );
        })}
      </ul>

      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-point`}>사용할 포인트</Label>
        <Input
          id={`${id}-point`}
          inputMode="numeric"
          pattern="\d*"
          placeholder="0"
          maxLength={9}
          value={point}
          onChange={(e) => setPoint(e.target.value)}
        />
        <p className="text-xs text-muted-foreground">
          {pointBalance == null
            ? "포인트 계정을 만드는 중이면 포인트 단계에서 실패하고 주문이 취소됩니다."
            : `보유 ${pointBalance.toLocaleString("ko-KR")}P. 잔액보다 많이 쓰면 포인트 단계에서 실패하고 앞 단계가 되돌려집니다.`}
        </p>
      </div>

      <dl className="grid grid-cols-2 gap-1 text-sm">
        <dt className="text-muted-foreground">주문 금액</dt>
        <dd className="text-right">{won(total)}</dd>
        <dt className="text-muted-foreground">포인트</dt>
        <dd className="text-right">-{pointAmount.toLocaleString("ko-KR")}P</dd>
        <dt className="font-medium">결제할 금액</dt>
        <dd className="text-right font-medium">{won(toPay)}</dd>
      </dl>

      <Button type="submit" disabled={pending}>
        {pending ? "주문하는 중..." : "주문하기"}
      </Button>
      <div id={errorId}>
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
      </div>
    </form>
  );
}
