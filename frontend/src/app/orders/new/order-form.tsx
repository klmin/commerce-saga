"use client";

import { type FormEvent, startTransition, useActionState, useId, useState } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { MAX_QUANTITY, orderTotal } from "@/lib/orders/order-input";
import { PRODUCTS, won } from "@/lib/products";

import { type OrderFormState, placeOrderAction } from "../order-actions";

type Props = {
  /** 폼을 열 때 서버가 정한 Idempotency-Key */
  idempotencyKey: string;
  /** 상품 id → 지금 주문 가능한 재고 (모르면 없음) */
  stocks: Record<number, number | undefined>;
  /** 내 포인트 잔액 (계정이 아직 없으면 없음) */
  pointBalance?: number;
  /** 상품 목록에서 고르고 온 상품 */
  initialProductId?: number;
};

function toNumber(text: string): number {
  return /^\d+$/.test(text) ? Number(text) : 0;
}

/**
 * 주문 폼. 수량·포인트는 화면 상태로 들고 있어 실패해도 입력이 남는다.
 * 제출은 직접 Server Action 을 부른다 (form action 으로 넘기면 React 가 제출 뒤 폼을 초기화한다)
 */
export function OrderForm({ idempotencyKey, stocks, pointBalance, initialProductId }: Props) {
  const [state, formAction, pending] = useActionState<OrderFormState, FormData>(placeOrderAction, {
    idempotencyKey,
  });
  const id = useId();
  const [quantities, setQuantities] = useState<Record<number, string>>(() =>
    Object.fromEntries(PRODUCTS.map((p) => [p.id, p.id === initialProductId ? "1" : ""])),
  );
  const [point, setPoint] = useState("");

  const total = orderTotal(
    Object.fromEntries(PRODUCTS.map((p) => [p.id, toNumber(quantities[p.id] ?? "")])),
  );
  const pointAmount = toNumber(point);
  const toPay = Math.max(0, total - pointAmount);

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    const formData = new FormData(event.currentTarget);
    startTransition(() => formAction(formData));
  }

  const errorId = `${id}-error`;
  return (
    <form
      // JS 가 붙기 전(하이드레이션 전)에 제출해도 Server Action 으로 간다. 붙은 뒤에는 onSubmit 이 직접 부른다
      action={formAction}
      onSubmit={onSubmit}
      className="flex flex-col gap-6"
      aria-describedby={errorId}
    >
      <input type="hidden" name="idempotencyKey" value={state.idempotencyKey} />
      <ul className="flex flex-col divide-y rounded-md border">
        {PRODUCTS.map((p) => {
          const stock = stocks[p.id];
          const soldOut = stock === 0;
          return (
            <li key={p.id} className="flex items-center gap-4 p-3">
              <div className="flex flex-1 flex-col">
                <Label htmlFor={`${id}-qty-${p.id}`}>{p.name}</Label>
                <span className="text-sm text-muted-foreground">
                  {won(p.price)} · {stock === undefined ? "재고 확인 불가" : `재고 ${stock}개`}
                  {soldOut && " (품절)"}
                </span>
              </div>
              <Input
                id={`${id}-qty-${p.id}`}
                name={`qty-${p.id}`}
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
          name="pointAmount"
          inputMode="numeric"
          pattern="\d*"
          placeholder="0"
          maxLength={9}
          value={point}
          onChange={(e) => setPoint(e.target.value)}
        />
        <p className="text-xs text-muted-foreground">
          {pointBalance === undefined
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
        {state.error && (
          <p role="alert" className="text-sm text-destructive">
            {state.error}
          </p>
        )}
      </div>
    </form>
  );
}
