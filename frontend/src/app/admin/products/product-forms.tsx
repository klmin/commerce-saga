"use client";

import { type FormEvent, type ReactNode, startTransition, useActionState, useId } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { MAX_DESCRIPTION_LENGTH, MAX_NAME_LENGTH } from "@/lib/admin/product-input";
import type { Product } from "@/lib/products";

import type { ProductFormState } from "./product-actions";

type Action = (prev: ProductFormState, formData: FormData) => Promise<ProductFormState>;

const initialState: ProductFormState = {};

function ActionForm({
  action,
  submitLabel,
  label,
  children,
}: {
  action: Action;
  submitLabel: string;
  label: string;
  children: ReactNode;
}) {
  const [state, formAction, pending] = useActionState(action, initialState);

  // React 는 form action 이 끝나면 비제어 입력을 초기값으로 되돌린다 → 저장이 실패하면 입력한 값이 사라진다.
  // 붙은 뒤에는 onSubmit 이 직접 불러 초기화를 피한다(결제 장애 폼과 같은 방식). 성공하면 등록은 이동하고,
  // 수정 폼은 다시 그린 새 값으로 key 가 바뀌어 다시 마운트된다
  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    const formData = new FormData(event.currentTarget);
    startTransition(() => formAction(formData));
  }

  return (
    <form
      // 하이드레이션 전 제출도 Server Action 으로
      action={formAction}
      onSubmit={onSubmit}
      aria-label={label}
      className="flex flex-col gap-3"
    >
      {children}
      {state.error && (
        <p role="alert" className="text-sm text-destructive">
          {state.error}
        </p>
      )}
      {state.saved && !state.error && (
        <p role="status" className="text-sm text-emerald-700 dark:text-emerald-400">
          {state.saved}
        </p>
      )}
      <Button type="submit" disabled={pending} className="self-start">
        {pending ? "저장 중..." : submitLabel}
      </Button>
    </form>
  );
}

function NameFields({ product }: { product?: Product }) {
  const id = useId();
  return (
    <>
      <div className="flex flex-col gap-1">
        <Label htmlFor={`${id}-name`}>상품 이름</Label>
        <Input
          id={`${id}-name`}
          name="name"
          required
          maxLength={MAX_NAME_LENGTH}
          defaultValue={product?.name}
        />
      </div>
      <div className="flex flex-col gap-1">
        <Label htmlFor={`${id}-description`}>설명</Label>
        <Textarea
          id={`${id}-description`}
          name="description"
          maxLength={MAX_DESCRIPTION_LENGTH}
          rows={2}
          defaultValue={product?.description}
        />
      </div>
    </>
  );
}

function PriceField({ price }: { price?: number }) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor={`${id}-price`}>가격 (원)</Label>
      <Input
        id={`${id}-price`}
        name="price"
        required
        inputMode="numeric"
        className="w-40"
        defaultValue={price}
      />
    </div>
  );
}

/** 새 상품 등록. 저장되면 그 상품의 관리 화면으로 간다 */
export function RegisterProductForm({ action }: { action: Action }) {
  return (
    <ActionForm action={action} submitLabel="등록" label="상품 등록">
      <NameFields />
      <PriceField />
    </ActionForm>
  );
}

/**
 * 상품 정보·가격·판매 상태 폼. 저장하면 서버 액션이 화면을 새로 그려(refresh) 바뀐 값과 이력이 보인다.
 * 입력은 비제어라 값이 바뀌면 key 로 다시 마운트해 새 값을 보여 준다 (폼의 저장 메시지는 남긴다)
 */
export function ProductEditForms({
  product,
  describeAction,
  priceAction,
  saleAction,
}: {
  product: Product;
  describeAction: Action;
  priceAction: Action;
  saleAction: Action;
}) {
  return (
    <div className="flex flex-col gap-6">
      <ActionForm action={describeAction} submitLabel="이름·설명 저장" label="정보 수정">
        <NameFields key={`${product.name}\n${product.description}`} product={product} />
      </ActionForm>
      <ActionForm action={priceAction} submitLabel="가격 변경" label="가격 변경">
        <PriceField key={product.price} price={product.price} />
      </ActionForm>
      <ActionForm
        action={saleAction}
        submitLabel={product.onSale ? "판매 중지" : "판매 재개"}
        label="판매 상태"
      >
        <input type="hidden" name="onSale" value={String(!product.onSale)} />
        <p className="text-sm">
          지금: <strong>{product.onSale ? "판매 중" : "판매 중지"}</strong>
          {product.onSale && (
            <span className="text-muted-foreground"> (중지하면 주문은 422, 검색에서 빠짐)</span>
          )}
        </p>
      </ActionForm>
    </div>
  );
}
