import { Suspense } from "react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiError } from "@/lib/api/client";
import { myPoints } from "@/lib/api/points";

// 로그인한 사용자 화면. 주문 화면은 F-1 다음 PR 에서 붙인다
// 가입 직후에는 포인트 계정이 아직 없을 수 있다: auth.events 로 몇 초 안에 생긴다 (시나리오 13)
async function balanceOrPending(): Promise<number | "pending"> {
  try {
    return (await myPoints()).balance;
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      return "pending";
    }
    throw e;
  }
}

async function PointBalance() {
  const balance = await balanceOrPending();
  if (balance === "pending") {
    return (
      <p className="text-sm text-muted-foreground">
        포인트 계정을 만드는 중입니다. 잠시 뒤 새로고침해 주세요.
      </p>
    );
  }
  return <p className="text-2xl font-semibold">{balance.toLocaleString("ko-KR")}P</p>;
}

export default function Home() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Card>
        <CardHeader>
          <CardTitle>내 포인트</CardTitle>
        </CardHeader>
        <CardContent>
          <Suspense
            fallback={
              <p role="status" className="text-sm text-muted-foreground">
                불러오는 중...
              </p>
            }
          >
            <PointBalance />
          </Suspense>
        </CardContent>
      </Card>
    </main>
  );
}
