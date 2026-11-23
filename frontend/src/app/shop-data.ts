import "server-only";

import { unstable_rethrow } from "next/navigation";

import { ApiError } from "@/lib/api/client";
import { myPoints } from "@/lib/api/points";
import { getStock } from "@/lib/api/stocks";
import { logError } from "@/lib/log";
import { PRODUCTS } from "@/lib/products";

/** 상품 id → 주문 가능한 재고. 한 상품을 못 읽어도 나머지는 보여 준다 (그 상품은 "확인 불가") */
export async function stocksByProduct(): Promise<Record<number, number | undefined>> {
  const results = await Promise.allSettled(PRODUCTS.map((p) => getStock(p.id)));
  return Object.fromEntries(
    PRODUCTS.map((p, i) => {
      const r = results[i];
      if (r.status === "fulfilled") {
        return [p.id, r.value.available];
      }
      // 로그인 화면으로 보내는 redirect 는 그대로 올린다
      unstable_rethrow(r.reason);
      if (!(r.reason instanceof ApiError)) {
        logError(`stock ${p.id} failed`, r.reason);
      }
      return [p.id, undefined];
    }),
  );
}

/** 내 포인트 잔액. 가입 직후 계정이 아직 없으면(404) undefined (시나리오 13) */
export async function pointBalanceOrUndefined(): Promise<number | undefined> {
  try {
    return (await myPoints()).balance;
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      return undefined;
    }
    throw e;
  }
}
