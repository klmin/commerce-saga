import "server-only";

import { z } from "zod";

import { FAULT_MODES, type FaultSettings } from "@/lib/admin/fault-input";

import { authedRequest } from "./authed";

const faultSettings = z.object({
  mode: z.enum(FAULT_MODES),
  delayMillis: z.number().int().nonnegative(),
});

/** 가짜 PG 장애 설정. ADMIN 이 아니면 Gateway 가 ApiError(403) */
export async function getPaymentFaults(): Promise<FaultSettings> {
  return faultSettings.parse(await authedRequest<unknown>("/api/admin/payment-faults"));
}

export async function setPaymentFaults(settings: FaultSettings): Promise<FaultSettings> {
  return faultSettings.parse(
    await authedRequest<unknown>("/api/admin/payment-faults", {
      method: "PUT",
      body: JSON.stringify(settings),
    }),
  );
}
