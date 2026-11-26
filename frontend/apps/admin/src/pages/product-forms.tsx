import { Button } from "@commerce/ui/button";
import { Input } from "@commerce/ui/input";
import { Label } from "@commerce/ui/label";
import { Textarea } from "@commerce/ui/textarea";
import { type FormEvent, type ReactNode, useId, useState } from "react";

import type { Product } from "@/api/admin";
import { failureMessage } from "@/lib/format";
import { MAX_DESCRIPTION_LENGTH, MAX_NAME_LENGTH } from "@/lib/product-input";

/** 저장 결과: 성공 문구 또는 화면에서 걸러낸 입력 오류 */
export type SubmitResult = { saved?: string; error?: string };

/**
 * 폼 하나. 입력은 비제어이고 실패해도 입력한 값이 남는다. 성공하면 부모가 다시 읽은 값으로 key 를 바꿔
 * 입력을 새 값으로 다시 그린다(저장 문구는 이 폼의 상태라 남는다)
 */
function ActionForm({
  onSubmit,
  submitLabel,
  label,
  children,
}: {
  onSubmit: (formData: FormData) => Promise<SubmitResult>;
  submitLabel: string;
  label: string;
  children: ReactNode;
}) {
  const [pending, setPending] = useState(false);
  const [state, setState] = useState<SubmitResult>({});

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    const formData = new FormData(event.currentTarget);
    setPending(true);
    try {
      setState(await onSubmit(formData));
    } catch (e) {
      setState({ error: failureMessage(e) });
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={submit} aria-label={label} className="flex flex-col gap-3" noValidate>
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

/** 새 상품 등록. 저장되면 부모가 그 상품의 관리 화면으로 옮긴다 */
export function RegisterProductForm({
  onSubmit,
}: {
  onSubmit: (formData: FormData) => Promise<SubmitResult>;
}) {
  return (
    <ActionForm onSubmit={onSubmit} submitLabel="등록" label="상품 등록">
      <NameFields />
      <PriceField />
    </ActionForm>
  );
}

/** 상품 정보·가격·판매 상태 폼. 값이 바뀌면 key 로 입력을 다시 그린다 */
export function ProductEditForms({
  product,
  onDescribe,
  onPrice,
  onSale,
}: {
  product: Product;
  onDescribe: (formData: FormData) => Promise<SubmitResult>;
  onPrice: (formData: FormData) => Promise<SubmitResult>;
  onSale: (formData: FormData) => Promise<SubmitResult>;
}) {
  return (
    <div className="flex flex-col gap-6">
      <ActionForm onSubmit={onDescribe} submitLabel="이름·설명 저장" label="정보 수정">
        <NameFields key={`${product.name}\n${product.description}`} product={product} />
      </ActionForm>
      <ActionForm onSubmit={onPrice} submitLabel="가격 변경" label="가격 변경">
        <PriceField key={product.price} price={product.price} />
      </ActionForm>
      <ActionForm
        onSubmit={onSale}
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
