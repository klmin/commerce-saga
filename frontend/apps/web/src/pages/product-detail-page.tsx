import { ApiError } from "@commerce/bff/api";
import { Button } from "@commerce/ui/button";
import { Input } from "@commerce/ui/input";
import { Label } from "@commerce/ui/label";
import { LoadError, Page } from "@commerce/ui/page";
import { FormSkeleton } from "@commerce/ui/skeletons";
import { useQuery } from "@tanstack/react-query";
import { useId, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";

import { getProduct, stocksByProduct } from "@/api/products";
import { ProductImage } from "@/components/product-image";
import { useAddToCart } from "@/hooks/use-add-to-cart";
import { useCategories } from "@/hooks/use-categories";
import { categoryUrl } from "@/lib/search";
import { MAX_QUANTITY, parseQuantity } from "@/lib/orders/order-input";
import { isProductId, type Product, won } from "@/lib/products";
import { stockLabel, stockState } from "@/lib/stock";

import { NotFoundPage } from "./not-found-page";

function Detail({ product }: { product: Product }) {
  const id = useId();
  const navigate = useNavigate();
  const { add, adding } = useAddToCart();
  const categories = useCategories();
  const stock = useQuery({
    queryKey: ["stocks", [product.id]],
    queryFn: () => stocksByProduct([product]),
  });
  const [quantity, setQuantity] = useState("1");
  const [error, setError] = useState<string>();
  const state = stockState(stock.data?.[product.id], stock.isPending);
  const category = categories.data?.find((c) => c.code === product.categoryCode);

  function withQuantity(next: (n: number) => void) {
    const n = parseQuantity(quantity);
    if (n === undefined) {
      setError(`수량은 1 ~ ${MAX_QUANTITY.toLocaleString("ko-KR")} 사이로 입력하세요.`);
      return;
    }
    setError(undefined);
    next(n);
  }

  return (
    <article className="grid gap-8 md:grid-cols-2">
      <ProductImage product={product} className="w-full" />
      <div className="flex flex-col gap-4">
        {category && (
          <Link
            to={categoryUrl(category.code, new URLSearchParams())}
            className="self-start text-sm text-muted-foreground hover:underline"
          >
            {category.name}
          </Link>
        )}
        <h1 className="text-2xl font-bold">{product.name}</h1>
        <p className="text-2xl font-semibold" data-testid="detail-price">
          {won(product.price)}
        </p>
        <p
          className={`text-sm ${
            state.kind === "soldOut" || state.kind === "low"
              ? "text-destructive"
              : "text-muted-foreground"
          }`}
          data-testid="detail-stock"
        >
          {stockLabel(state)}
        </p>
        {product.description && (
          <p className="text-sm whitespace-pre-line text-muted-foreground">{product.description}</p>
        )}

        {product.onSale ? (
          <form
            className="flex flex-col gap-3 border-t pt-4"
            noValidate
            onSubmit={(e) => {
              e.preventDefault();
              withQuantity((n) => void add(product.id, n));
            }}
          >
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-quantity`}>수량</Label>
              <Input
                id={`${id}-quantity`}
                inputMode="numeric"
                pattern="\d*"
                maxLength={String(MAX_QUANTITY).length}
                className="w-24 text-right"
                value={quantity}
                onChange={(e) => setQuantity(e.target.value)}
              />
            </div>
            {state.kind === "soldOut" && (
              <p className="text-sm text-muted-foreground">
                품절이어도 주문할 수 있습니다. 재고 예약 단계에서 실패하고 주문이 취소되는 과정을
                주문 상세에서 볼 수 있습니다.
              </p>
            )}
            <div className="flex flex-wrap gap-2">
              <Button type="submit" variant="outline" disabled={adding !== undefined}>
                장바구니 담기
              </Button>
              <Button
                type="button"
                onClick={() =>
                  withQuantity((n) =>
                    navigate(
                      `/orders/new?productId=${encodeURIComponent(product.id)}&quantity=${n}`,
                    ),
                  )
                }
              >
                바로 주문
              </Button>
            </div>
            {error && (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            )}
            <p className="text-xs text-muted-foreground">
              가격은 주문할 때 다시 확인합니다. 그사이 바뀌면 바뀐 가격을 보여 드리고 확인을
              받습니다.
            </p>
          </form>
        ) : (
          <p role="status" className="border-t pt-4 text-sm font-medium text-destructive">
            판매가 중지된 상품입니다. 주문할 수 없습니다.
          </p>
        )}
      </div>
    </article>
  );
}

export function ProductDetailPage() {
  const { productId: raw = "" } = useParams();
  const valid = isProductId(raw);
  const productId = raw.toLowerCase();
  const product = useQuery({
    queryKey: ["product", productId],
    queryFn: () => getProduct(productId),
    enabled: valid,
  });
  // UUID 가 아니거나 없는 상품이면 같은 화면
  if (!valid || (product.error instanceof ApiError && product.error.status === 404)) {
    return <NotFoundPage />;
  }
  return (
    <Page wide>
      <Button variant="ghost" size="sm" asChild className="self-start">
        <Link to="/">← 상품 목록</Link>
      </Button>
      {product.isError ? (
        <LoadError error={product.error} retry={() => product.refetch()} />
      ) : product.isPending ? (
        <FormSkeleton fields={4} />
      ) : (
        <Detail product={product.data} />
      )}
    </Page>
  );
}
