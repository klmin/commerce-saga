import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { listAllProducts } from "@/lib/api/admin-products";
import { ApiError } from "@/lib/api/client";
import { isAdmin } from "@/lib/auth/server-session";
import { won } from "@/lib/products";

import { registerProductAction } from "./product-actions";
import { RegisterProductForm } from "./product-forms";

export const metadata: Metadata = { title: "상품 관리 · commerce-saga" };

function pageNumber(raw: string | string[] | undefined): number {
  const n = Number(Array.isArray(raw) ? raw[0] : raw);
  return Number.isInteger(n) && n >= 0 && n < 10_000 ? n : 0;
}

// 관리자가 아니면 화면이 없는 것처럼(404). 권한 검사는 Gateway(/api/admin/** = ADMIN)가 한다
async function ProductTable({ searchParams }: Pick<PageProps<"/admin/products">, "searchParams">) {
  if (!(await isAdmin())) {
    notFound();
  }
  const page = pageNumber((await searchParams).page);
  let result;
  try {
    result = await listAllProducts(page);
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      notFound();
    }
    throw e;
  }
  return (
    <div className="flex flex-col gap-3">
      <ul aria-label="관리 상품 목록" className="flex flex-col divide-y text-sm">
        {result.items.map((p) => (
          <li key={p.id} className="flex items-center justify-between gap-2 py-2">
            <span className="flex flex-col">
              <span className="font-medium">
                <span className="text-muted-foreground">#{p.id}</span> {p.name}
              </span>
              <span className="text-muted-foreground">
                {won(p.price)} · {p.onSale ? "판매 중" : "판매 중지"}
              </span>
            </span>
            <Button variant="outline" size="sm" asChild>
              <Link href={`/admin/products/${p.id}`} aria-label={`${p.name} 관리`}>
                관리
              </Link>
            </Button>
          </li>
        ))}
      </ul>
      <nav className="flex gap-2">
        {page > 0 && (
          <Button variant="ghost" size="sm" asChild>
            <Link href={`/admin/products?page=${page - 1}`}>이전</Link>
          </Button>
        )}
        {result.hasNext && (
          <Button variant="ghost" size="sm" asChild>
            <Link href={`/admin/products?page=${page + 1}`}>다음</Link>
          </Button>
        )}
      </nav>
    </div>
  );
}

export default function AdminProductsPage({ searchParams }: PageProps<"/admin/products">) {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Card>
        <CardHeader>
          <CardTitle>상품 관리</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          <p className="text-sm text-muted-foreground">
            판매 중지 상품까지 모두 보인다. 바꾸면 누가·언제·무엇을 바꿨는지 관리 이력에 남는다(같은
            트랜잭션). 검색에는 CDC 로 몇 초 뒤 반영된다.
          </p>
          <Suspense
            fallback={
              <p role="status" className="text-sm text-muted-foreground">
                불러오는 중...
              </p>
            }
          >
            <ProductTable searchParams={searchParams} />
          </Suspense>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>상품 등록</CardTitle>
        </CardHeader>
        <CardContent>
          <RegisterProductForm action={registerProductAction} />
        </CardContent>
      </Card>
    </main>
  );
}
