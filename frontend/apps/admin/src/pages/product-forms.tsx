import { Button } from "@commerce/ui/button";
import { Input } from "@commerce/ui/input";
import { Label } from "@commerce/ui/label";
import { Textarea } from "@commerce/ui/textarea";
import {
  type ChangeEvent,
  type FormEvent,
  type ReactNode,
  useEffect,
  useId,
  useState,
} from "react";

import type { Category, Product } from "@/api/admin";
import { failureMessage } from "@/lib/format";
import {
  IMAGE_TYPES,
  imageUrl,
  MAX_DESCRIPTION_LENGTH,
  MAX_NAME_LENGTH,
} from "@/lib/product-input";

/** 저장 결과: 성공 문구 또는 화면에서 걸러낸 입력 오류 */
export type SubmitResult = { saved?: string; error?: string };

/**
 * 폼 하나. 입력은 비제어이고 실패해도 입력한 값이 남는다. 성공하면 부모가 다시 읽은 값으로 key 를 바꿔
 * 입력을 새 값으로 다시 그린다(저장 문구는 이 폼의 상태라 남는다). resetOnSuccess 면 성공한 뒤 입력을 비운다
 * (재고 조정처럼 같은 값을 다시 누르면 한 번 더 반영되는 폼)
 */
export function ActionForm({
  onSubmit,
  submitLabel,
  label,
  resetOnSuccess = false,
  children,
}: {
  onSubmit: (formData: FormData) => Promise<SubmitResult>;
  submitLabel: string;
  label: string;
  resetOnSuccess?: boolean;
  children: ReactNode;
}) {
  const [pending, setPending] = useState(false);
  const [state, setState] = useState<SubmitResult>({});

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    const form = event.currentTarget;
    const formData = new FormData(form);
    setPending(true);
    try {
      const result = await onSubmit(formData);
      if (resetOnSuccess && result.saved && !result.error) {
        form.reset();
      }
      setState(result);
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

function NameFields({ product, categories }: { product?: Product; categories: Category[] }) {
  const id = useId();
  return (
    <>
      <div className="flex flex-col gap-1">
        <Label htmlFor={`${id}-category`}>카테고리</Label>
        <select
          id={`${id}-category`}
          name="categoryCode"
          required
          defaultValue={product?.categoryCode ?? ""}
          className="h-9 w-48 rounded-md border border-input bg-transparent px-2 text-sm dark:bg-input/30"
        >
          {!product && (
            <option value="" disabled>
              고르세요
            </option>
          )}
          {categories.map((c) => (
            <option key={c.code} value={c.code}>
              {c.name}
            </option>
          ))}
        </select>
      </div>
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
  categories,
  onSubmit,
}: {
  categories: Category[];
  onSubmit: (formData: FormData) => Promise<SubmitResult>;
}) {
  return (
    <ActionForm onSubmit={onSubmit} submitLabel="등록" label="상품 등록">
      <NameFields categories={categories} />
      <PriceField />
    </ActionForm>
  );
}

/** 상품 정보·가격·판매 상태 폼. 값이 바뀌면 key 로 입력을 다시 그린다 */
export function ProductEditForms({
  product,
  categories,
  onDescribe,
  onImage,
  onPrice,
  onSale,
}: {
  product: Product;
  categories: Category[];
  onImage: (formData: FormData) => Promise<SubmitResult>;
  onDescribe: (formData: FormData) => Promise<SubmitResult>;
  onPrice: (formData: FormData) => Promise<SubmitResult>;
  onSale: (formData: FormData) => Promise<SubmitResult>;
}) {
  return (
    <div className="flex flex-col gap-6">
      <ActionForm onSubmit={onDescribe} submitLabel="정보 저장" label="정보 수정">
        <NameFields
          key={`${product.name}\n${product.description}\n${product.categoryCode}`}
          product={product}
          categories={categories}
        />
      </ActionForm>
      <ActionForm onSubmit={onImage} submitLabel="이미지 올리기" label="대표 이미지">
        <ImageField key={product.imagePath ?? ""} product={product} />
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

/** 상품 대표 이미지 (목록 썸네일·관리 화면). 없으면 빈 칸 */
export function ProductThumb({
  product,
  size = "sm",
}: {
  product: Pick<Product, "name" | "imagePath">;
  size?: "sm" | "lg";
}) {
  const src = imageUrl(product.imagePath);
  const box =
    size === "sm" ? "size-12 rounded-md border bg-muted" : "size-40 rounded-lg border bg-muted";
  if (!src) {
    return (
      <span
        aria-label="이미지 없음"
        className={`${box} flex shrink-0 items-center justify-center text-xs text-muted-foreground`}
      >
        없음
      </span>
    );
  }
  return (
    <img
      src={src}
      alt={`${product.name} 이미지`}
      loading="lazy"
      className={`${box} shrink-0 object-cover`}
    />
  );
}

/**
 * 대표 이미지 고르기. 고른 파일을 올리기 전에 미리 본다(브라우저 안의 object URL, 서버로 가지 않는다).
 * 올리면 부모가 다시 읽은 imagePath 로 key 를 바꿔 새 이미지를 보이고 고른 파일을 비운다
 */
function ImageField({ product }: { product: Product }) {
  const id = useId();
  const [preview, setPreview] = useState<string>();

  // 다른 파일을 고르거나 화면을 떠나면 앞의 미리보기 URL 을 놓아준다
  useEffect(() => {
    return () => {
      if (preview) {
        URL.revokeObjectURL(preview);
      }
    };
  }, [preview]);

  function choose(event: ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0];
    setPreview(file && file.type.startsWith("image/") ? URL.createObjectURL(file) : undefined);
  }

  return (
    <div className="flex flex-wrap items-start gap-4">
      <figure className="flex flex-col items-center gap-1">
        <ProductThumb product={product} size="lg" />
        <figcaption className="text-xs text-muted-foreground">지금</figcaption>
      </figure>
      {preview && (
        <figure className="flex flex-col items-center gap-1">
          <img
            src={preview}
            alt="올릴 이미지 미리보기"
            className="size-40 rounded-lg border bg-muted object-cover"
          />
          <figcaption className="text-xs text-muted-foreground">올릴 이미지</figcaption>
        </figure>
      )}
      <div className="flex flex-col gap-1">
        <Label htmlFor={`${id}-file`}>이미지 파일</Label>
        <Input
          id={`${id}-file`}
          name="file"
          type="file"
          required
          accept={IMAGE_TYPES.join(",")}
          onChange={choose}
          className="w-72"
        />
        <p className="text-xs text-muted-foreground">jpg·png·webp, 2MB 까지</p>
      </div>
    </div>
  );
}
