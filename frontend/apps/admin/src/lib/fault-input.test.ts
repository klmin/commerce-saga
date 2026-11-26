import { describe, expect, it } from "vitest";

import { parseFaultInput } from "./fault-input";

function form(fields: Record<string, string>) {
  const data = new FormData();
  for (const [k, v] of Object.entries(fields)) {
    data.set(k, v);
  }
  return data;
}

describe("parseFaultInput", () => {
  it("승인·거절은 지연 0", () => {
    expect(parseFaultInput(form({ mode: "APPROVE", delayMillis: "4000" }))).toEqual({
      ok: true,
      value: { mode: "APPROVE", delayMillis: 0 },
    });
    expect(parseFaultInput(form({ mode: "DECLINE" }))).toEqual({
      ok: true,
      value: { mode: "DECLINE", delayMillis: 0 },
    });
  });

  it("지연은 0 ~ 60,000ms 정수", () => {
    expect(parseFaultInput(form({ mode: "DELAY", delayMillis: "4000" }))).toEqual({
      ok: true,
      value: { mode: "DELAY", delayMillis: 4000 },
    });
    expect(parseFaultInput(form({ mode: "DELAY", delayMillis: "60000" })).ok).toBe(true);
    expect(parseFaultInput(form({ mode: "DELAY", delayMillis: "60001" })).ok).toBe(false);
    expect(parseFaultInput(form({ mode: "DELAY", delayMillis: "-1" })).ok).toBe(false);
    expect(parseFaultInput(form({ mode: "DELAY", delayMillis: "1.5" })).ok).toBe(false);
    expect(parseFaultInput(form({ mode: "DELAY", delayMillis: "" })).ok).toBe(false);
  });

  it("모르는 모드는 거부", () => {
    expect(parseFaultInput(form({ mode: "TIMEOUT" })).ok).toBe(false);
    expect(parseFaultInput(new FormData()).ok).toBe(false);
  });
});
