"use server";

import { unstable_rethrow } from "next/navigation";

import { parseFaultInput, type FaultSettings } from "@/lib/admin/fault-input";
import { setPaymentFaults } from "@/lib/api/admin";
import { ApiError } from "@/lib/api/client";
import { logError } from "@/lib/log";

export type FaultFormState = { error?: string; saved?: FaultSettings };

/** 결제 장애 주입 변경. 권한(ADMIN)은 Gateway·payment-service 가 토큰으로 검사한다 */
export async function setPaymentFaultsAction(
  _prev: FaultFormState,
  formData: FormData,
): Promise<FaultFormState> {
  const input = parseFaultInput(formData);
  if (!input.ok) {
    return { error: input.error };
  }
  try {
    return { saved: await setPaymentFaults(input.value) };
  } catch (e) {
    // 로그인이 풀려 authedRequest 가 던지는 redirect 는 그대로 올린다
    unstable_rethrow(e);
    if (e instanceof ApiError) {
      return { error: e.status === 403 ? "관리자만 바꿀 수 있습니다." : e.message };
    }
    logError("setPaymentFaultsAction failed", e);
    return { error: "서버에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요." };
  }
}
