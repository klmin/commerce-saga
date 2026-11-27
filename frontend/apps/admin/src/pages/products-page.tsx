import { Button } from "@commerce/ui/button";
import { LoadError, Page, Section } from "@commerce/ui/page";
import { ListSkeleton } from "@commerce/ui/skeletons";
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearchParams } from "react-router";

import { listAllProducts, registerProduct, shortProductId } from "@/api/admin";
import { pageNumber, won } from "@/lib/format";
import { parseRegisterInput } from "@/lib/product-input";

import { RegisterProductForm, type SubmitResult } from "./product-forms";

function ProductTable() {
  const [params] = useSearchParams();
  const page = pageNumber(params.get("page"));
  const products = useQuery({
    queryKey: ["admin-products", page],
    queryFn: () => listAllProducts(page),
  });
  if (products.isPending) {
    return <ListSkeleton rows={5} />;
  }
  if (products.isError) {
    return <LoadError error={products.error} retry={() => products.refetch()} />;
  }
  return (
    <div className="flex flex-col gap-3">
      <ul aria-label="관리 상품 목록" className="flex flex-col divide-y text-sm">
        {products.data.items.map((p) => (
          <li key={p.id} className="flex items-center justify-between gap-2 py-2">
            <span className="flex flex-col">
              <span className="font-medium">
                <span className="text-muted-foreground" title={p.id}>
                  #{shortProductId(p.id)}
                </span>{" "}
                {p.name}
              </span>
              <span className="text-muted-foreground">
                {won(p.price)} · {p.onSale ? "판매 중" : "판매 중지"}
              </span>
            </span>
            <Button variant="outline" size="sm" asChild>
              <Link to={`/products/${p.id}`} aria-label={`${p.name} 관리`}>
                관리
              </Link>
            </Button>
          </li>
        ))}
      </ul>
      <nav className="flex gap-2">
        {page > 0 && (
          <Button variant="ghost" size="sm" asChild>
            <Link to={`/products?page=${page - 1}`}>이전</Link>
          </Button>
        )}
        {products.data.hasNext && (
          <Button variant="ghost" size="sm" asChild>
            <Link to={`/products?page=${page + 1}`}>다음</Link>
          </Button>
        )}
      </nav>
    </div>
  );
}

export function ProductsPage() {
  const navigate = useNavigate();

  async function register(formData: FormData): Promise<SubmitResult> {
    const input = parseRegisterInput(formData);
    if (!input.ok) {
      return { error: input.error };
    }
    const product = await registerProduct(input.value);
    navigate(`/products/${product.id}`);
    return {};
  }

  return (
    <Page>
      <Section title="상품 관리">
        <p className="text-sm text-muted-foreground">
          판매 중지 상품까지 모두 보인다. 바꾸면 누가·언제·무엇을 바꿨는지 관리 이력에 남는다(같은
          트랜잭션). 검색에는 CDC 로 몇 초 뒤 반영된다.
        </p>
        <ProductTable />
      </Section>
      <Section title="상품 등록">
        <RegisterProductForm onSubmit={register} />
      </Section>
    </Page>
  );
}
