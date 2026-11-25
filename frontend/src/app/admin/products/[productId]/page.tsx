import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { AUDIT_ACTION_LABEL, auditChanges } from "@/lib/admin/product-input";
import { getProduct, productAuditLog } from "@/lib/api/admin-products";
import { ApiError } from "@/lib/api/client";
import { isAdmin } from "@/lib/auth/server-session";
import { formatTime } from "@/lib/orders/format";

import {
  changePriceAction,
  changeSaleStatusAction,
  describeProductAction,
} from "../product-actions";
import { ProductEditForms } from "../product-forms";

export const metadata: Metadata = { title: "상품 관리 · commerce-saga" };

function pageNumber(raw: string | string[] | undefined): number {
  const n = Number(Array.isArray(raw) ? raw[0] : raw);
  return Number.isInteger(n) && n >= 0 && n < 10_000 ? n : 0;
}

async function load(productId: number, page: number) {
  try {
    return await Promise.all([getProduct(productId), productAuditLog(productId, page)]);
  } catch (e) {
    // 관리자가 아니면(403) 없는 화면처럼, 없는 상품도 404
    if (e instanceof ApiError && (e.status === 403 || e.status === 404)) {
      notFound();
    }
    throw e;
  }
}

async function ProductManagement({
  params,
  searchParams,
}: PageProps<"/admin/products/[productId]">) {
  if (!(await isAdmin())) {
    notFound();
  }
  const raw = (await params).productId;
  const productId = Number(raw);
  if (!/^\d{1,18}$/.test(raw) || !Number.isSafeInteger(productId) || productId < 1) {
    notFound();
  }
  const page = pageNumber((await searchParams).page);
  const [product, audit] = await load(productId, page);

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>
            <span className="text-muted-foreground">#{product.id}</span> {product.name}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <ProductEditForms
            product={product}
            describeAction={describeProductAction.bind(null, product.id)}
            priceAction={changePriceAction.bind(null, product.id)}
            saleAction={changeSaleStatusAction.bind(null, product.id)}
          />
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>관리 이력</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <p className="text-sm text-muted-foreground">
            최근 변경부터. 바꾼 사람은 관리자 사용자 id(토큰의 sub). 관리 이력 이전의 가격 변경은
            가격만 남아 있다.
          </p>
          {audit.items.length === 0 ? (
            <p className="text-sm text-muted-foreground">이력이 없습니다.</p>
          ) : (
            <ol aria-label="관리 이력" className="flex flex-col divide-y text-sm">
              {audit.items.map((entry, i) => (
                <li key={`${entry.changedAt}-${i}`} className="flex flex-col gap-1 py-2">
                  <span className="flex flex-wrap items-baseline gap-x-2">
                    <strong>{AUDIT_ACTION_LABEL[entry.action]}</strong>
                    <span className="text-muted-foreground">
                      {formatTime(entry.changedAt)} · 관리자 #{entry.changedBy}
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
                <Link href={`/admin/products/${product.id}?page=${page - 1}`}>최근 이력</Link>
              </Button>
            )}
            {audit.hasNext && (
              <Button variant="ghost" size="sm" asChild>
                <Link href={`/admin/products/${product.id}?page=${page + 1}`}>이전 이력</Link>
              </Button>
            )}
          </nav>
        </CardContent>
      </Card>
    </>
  );
}

export default function AdminProductPage(props: PageProps<"/admin/products/[productId]">) {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Button variant="ghost" size="sm" asChild className="self-start">
        <Link href="/admin/products">← 상품 관리</Link>
      </Button>
      <Suspense
        fallback={
          <p role="status" className="text-sm text-muted-foreground">
            불러오는 중...
          </p>
        }
      >
        <ProductManagement {...props} />
      </Suspense>
    </main>
  );
}
