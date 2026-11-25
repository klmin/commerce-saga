import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense } from "react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { getPaymentFaults } from "@/lib/api/admin";
import { ApiError } from "@/lib/api/client";
import { isAdmin } from "@/lib/auth/server-session";

import { setPaymentFaultsAction } from "./admin-actions";
import { FaultForm } from "./fault-form";

export const metadata: Metadata = { title: "관리자 · commerce-saga" };

// 관리자가 아니면 화면이 없는 것처럼(404). 메뉴 숨김은 편의이고 권한 검사는 Gateway(/api/admin/** = ADMIN)가 한다
async function PaymentFaults() {
  if (!(await isAdmin())) {
    notFound();
  }
  let current;
  try {
    current = await getPaymentFaults();
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      notFound();
    }
    throw e;
  }
  return <FaultForm current={current} action={setPaymentFaultsAction} />;
}

export default function AdminPage() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Card>
        <CardHeader>
          <CardTitle>상품 관리</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2 text-sm">
          <p className="text-muted-foreground">
            상품 등록·이름·설명·가격·판매 상태를 바꾸고, 상품마다 누가·언제·무엇을 바꿨는지 관리
            이력을 본다.
          </p>
          <Link href="/admin/products" className="self-start underline underline-offset-4">
            상품 관리로 가기
          </Link>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>결제 장애 주입</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <p className="text-sm text-muted-foreground">
            가짜 PG 의 결과를 바꿔 결제 실패 보상(포인트 환불 → 재고 해제 → 취소)과 결제 지연을
            화면에서 재현한다. 모든 사용자의 주문에 적용되니 확인한 뒤 &quot;정상 승인&quot;으로
            되돌린다.
          </p>
          <Suspense
            fallback={
              <p role="status" className="text-sm text-muted-foreground">
                불러오는 중...
              </p>
            }
          >
            <PaymentFaults />
          </Suspense>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>DLT (처리하지 못한 메시지)</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2 text-sm">
          <p className="text-muted-foreground">
            재시도해도 처리할 수 없는 메시지는 각 토픽의 <code>&lt;토픽&gt;.DLT</code> 에 원본
            그대로 보관된다(ADR 0012). 화면 대신 운영 도구로 본다.
          </p>
          <ul className="list-disc pl-5 text-muted-foreground">
            <li>
              조회: Kafka UI <code>http://localhost:8989</code> 의 Topics 에서 <code>*.DLT</code>
            </li>
            <li>
              목록·재처리: <code>scripts/dlt.sh list point.command.DLT</code>,{" "}
              <code>scripts/dlt.sh replay point.command.DLT --offset 1:0 --dry-run</code> (시나리오
              6)
            </li>
          </ul>
        </CardContent>
      </Card>
    </main>
  );
}
