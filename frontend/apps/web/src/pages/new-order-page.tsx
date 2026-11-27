import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router";

import { myPointBalance } from "@/api/points";
import { listProducts, stocksByProduct } from "@/api/products";
import { OrderForm } from "@/components/order-form";
import { LoadError, Page, Section } from "@commerce/ui/page";
import { FormSkeleton } from "@commerce/ui/skeletons";
import { isProductId } from "@/lib/products";

export function NewOrderPage() {
  const [params] = useSearchParams();
  // 형식이 틀린 값(옛 숫자 id 등)이면 고른 상품 없이 연다
  const raw = params.get("productId");
  const productId = isProductId(raw) ? raw.toLowerCase() : undefined;
  const products = useQuery({ queryKey: ["products"], queryFn: listProducts });
  const stocks = useQuery({
    queryKey: ["stocks", products.data?.map((p) => p.id)],
    queryFn: () => stocksByProduct(products.data ?? []),
    enabled: products.isSuccess,
  });
  const balance = useQuery({ queryKey: ["points", "balance"], queryFn: myPointBalance });

  return (
    <Page>
      <Section title="주문하기">
        {products.isError ? (
          <LoadError error={products.error} retry={() => products.refetch()} />
        ) : products.isPending || stocks.isPending ? (
          <FormSkeleton fields={3} />
        ) : (
          <OrderForm
            // 화면을 열 때마다 새 Idempotency-Key (폼 안에서 정함). 이 폼에서 다시 누르는 것은 같은 주문으로 처리된다
            key={productId ?? ""}
            products={products.data}
            stocks={stocks.data ?? {}}
            pointBalance={balance.data}
            initialProductId={products.data.find((p) => p.id === productId)?.id}
          />
        )}
      </Section>
    </Page>
  );
}
