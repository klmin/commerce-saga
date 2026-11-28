import { useState } from "react";

import { imageUrl, type Product } from "@/lib/products";

/**
 * 상품 대표 이미지. 없거나 못 읽으면 이름 첫 글자 자리 표시. 이름이 옆에 같이 보이는 목록에서는 alt 를 비워(decorative)
 * 화면 읽기 프로그램이 이름을 두 번 읽지 않게 한다
 */
export function ProductImage({
  product,
  className = "",
  decorative = false,
}: {
  product: Pick<Product, "name" | "imagePath">;
  className?: string;
  decorative?: boolean;
}) {
  const [failed, setFailed] = useState(false);
  const src = failed ? undefined : imageUrl(product.imagePath);
  const box = `aspect-square overflow-hidden rounded-md bg-muted ${className}`;
  if (!src) {
    return (
      <div
        role={decorative ? undefined : "img"}
        aria-label={decorative ? undefined : `${product.name} (이미지 없음)`}
        aria-hidden={decorative ? true : undefined}
        className={`${box} flex items-center justify-center text-2xl font-semibold text-muted-foreground`}
      >
        {product.name.slice(0, 1)}
      </div>
    );
  }
  return (
    <img
      src={src}
      alt={decorative ? "" : product.name}
      loading="lazy"
      onError={() => setFailed(true)}
      className={`${box} object-cover`}
    />
  );
}
