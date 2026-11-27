import { ApiError } from "@commerce/bff/api";
import { shortId } from "@commerce/bff/short-id";
import { Button } from "@commerce/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@commerce/ui/card";
import { LoadError, Page } from "@commerce/ui/page";
import { FormSkeleton } from "@commerce/ui/skeletons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams, useSearchParams } from "react-router";

import {
  changeProductPrice,
  changeProductSaleStatus,
  describeProduct,
  getProduct,
  isProductId,
  productAuditLog,
  type ProductId,
  shortProductId,
} from "@/api/admin";
import { formatTime, pageNumber } from "@/lib/format";
import {
  AUDIT_ACTION_LABEL,
  auditChanges,
  parseDescribeInput,
  parsePriceInput,
  parseSaleInput,
} from "@/lib/product-input";

import { NotFoundPage } from "./not-found-page";
import { ProductEditForms, type SubmitResult } from "./product-forms";
import { StockSection } from "./stock-section";

function ProductManagement({ productId }: { productId: ProductId }) {
  const queryClient = useQueryClient();
  const [params] = useSearchParams();
  const page = pageNumber(params.get("page"));
  const product = useQuery({
    queryKey: ["admin-product", productId],
    queryFn: () => getProduct(productId),
  });
  const audit = useQuery({
    queryKey: ["admin-product", productId, "audit", page],
    queryFn: () => productAuditLog(productId, page),
  });

  /** 바꾼 뒤 상품·이력·목록을 다시 읽는다 */
  async function after(saved: string): Promise<SubmitResult> {
    await queryClient.invalidateQueries({ queryKey: ["admin-product", productId] });
    await queryClient.invalidateQueries({ queryKey: ["admin-products"] });
    return { saved };
  }

  async function onDescribe(formData: FormData): Promise<SubmitResult> {
    const input = parseDescribeInput(formData);
    if (!input.ok) {
      return { error: input.error };
    }
    await describeProduct(productId, input.value);
    return after("이름·설명을 저장했습니다.");
  }

  async function onPrice(formData: FormData): Promise<SubmitResult> {
    const input = parsePriceInput(formData);
    if (!input.ok) {
      return { error: input.error };
    }
    await changeProductPrice(productId, input.value);
    return after(`가격을 ${input.value.toLocaleString("ko-KR")}원으로 바꿨습니다.`);
  }

  async function onSale(formData: FormData): Promise<SubmitResult> {
    const input = parseSaleInput(formData);
    if (!input.ok) {
      return { error: input.error };
    }
    await changeProductSaleStatus(productId, input.value);
    return after(input.value ? "판매를 다시 시작했습니다." : "판매를 중지했습니다.");
  }

  const error = product.error ?? audit.error;
  if (error instanceof ApiError && error.status === 404) {
    return <NotFoundPage embedded />;
  }
  if (error && !(product.data && audit.data)) {
    return <LoadError error={error} retry={() => void queryClient.invalidateQueries()} />;
  }
  if (!product.data || !audit.data) {
    return <FormSkeleton fields={3} />;
  }

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>
            <span className="text-muted-foreground" title={product.data.id}>
              #{shortProductId(product.data.id)}
            </span>{" "}
            {product.data.name}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <ProductEditForms
            product={product.data}
            onDescribe={onDescribe}
            onPrice={onPrice}
            onSale={onSale}
          />
        </CardContent>
      </Card>
      <StockSection productId={productId} />
      <Card>
        <CardHeader>
          <CardTitle>관리 이력</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <p className="text-sm text-muted-foreground">
            최근 변경부터. 바꾼 사람은 관리자 사용자 id(토큰의 sub). 관리 이력 이전의 가격 변경은
            가격만 남아 있다.
          </p>
          {audit.data.items.length === 0 ? (
            <p className="text-sm text-muted-foreground">이력이 없습니다.</p>
          ) : (
            <ol aria-label="관리 이력" className="flex flex-col divide-y text-sm">
              {audit.data.items.map((entry, i) => (
                <li key={`${entry.changedAt}-${i}`} className="flex flex-col gap-1 py-2">
                  <span className="flex flex-wrap items-baseline gap-x-2">
                    <strong>{AUDIT_ACTION_LABEL[entry.action]}</strong>
                    <span className="text-muted-foreground" title={entry.changedBy}>
                      {formatTime(entry.changedAt)} · 관리자 #{shortId(entry.changedBy)}
                    </span>
                  </span>
                  <ul className="flex flex-col gap-0.5 pl-2">
                    {auditChanges(entry).map((c) => (
                      <li key={c.label}>
                        {c.label}:{" "}
                        {c.before === null ? (
                          c.after
                        ) : (
                          <>
                            <span className="text-muted-foreground line-through">{c.before}</span> →{" "}
                            {c.after}
                          </>
                        )}
                      </li>
                    ))}
                  </ul>
                </li>
              ))}
            </ol>
          )}
          <nav className="flex gap-2">
            {page > 0 && (
              <Button variant="ghost" size="sm" asChild>
                <Link to={`/products/${productId}?page=${page - 1}`}>최근 이력</Link>
              </Button>
            )}
            {audit.data.hasNext && (
              <Button variant="ghost" size="sm" asChild>
                <Link to={`/products/${productId}?page=${page + 1}`}>이전 이력</Link>
              </Button>
            )}
          </nav>
        </CardContent>
      </Card>
    </>
  );
}

export function ProductPage() {
  const { productId: raw = "" } = useParams();
  // UUID 형식이 아니면(옛 숫자 id 등) 요청하지 않고 없는 화면
  const valid = isProductId(raw);
  const productId = raw.toLowerCase();
  return (
    <Page>
      <Button variant="ghost" size="sm" asChild className="self-start">
        <Link to="/products">← 상품 관리</Link>
      </Button>
      {valid ? (
        <ProductManagement key={productId} productId={productId} />
      ) : (
        <NotFoundPage embedded />
      )}
    </Page>
  );
}
