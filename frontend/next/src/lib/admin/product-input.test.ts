import { describe, expect, it } from "vitest";

import {
  type AuditEntry,
  auditChanges,
  parseDescribeInput,
  parsePriceInput,
  parseRegisterInput,
  parseSaleInput,
} from "./product-input";

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [k, v] of Object.entries(values)) {
    data.set(k, v);
  }
  return data;
}

describe("parse", () => {
  it("이름·설명을 다듬고 길이를 검사한다", () => {
    expect(parseDescribeInput(form({ name: " 키보드 ", description: " 무선 " }))).toEqual({
      ok: true,
      value: { name: "키보드", description: "무선" },
    });
    expect(parseDescribeInput(form({ name: "  " })).ok).toBe(false);
    expect(parseDescribeInput(form({ name: "가".repeat(101) })).ok).toBe(false);
    expect(parseDescribeInput(form({ name: "a", description: "가".repeat(501) })).ok).toBe(false);
  });

  it("가격은 1원 ~ 1억 원 정수, 쉼표는 허용", () => {
    expect(parsePriceInput(form({ price: "35,000" }))).toEqual({ ok: true, value: 35_000 });
    expect(parsePriceInput(form({ price: "0" })).ok).toBe(false);
    expect(parsePriceInput(form({ price: "100000001" })).ok).toBe(false);
    expect(parsePriceInput(form({ price: "1.5" })).ok).toBe(false);
    expect(parsePriceInput(form({ price: "-1" })).ok).toBe(false);
  });

  it("등록은 이름·가격 둘 다", () => {
    expect(parseRegisterInput(form({ name: "허브", price: "18000" }))).toEqual({
      ok: true,
      value: { name: "허브", description: "", price: 18_000 },
    });
    expect(parseRegisterInput(form({ name: "허브" })).ok).toBe(false);
  });

  it("판매 상태는 true/false 만", () => {
    expect(parseSaleInput(form({ onSale: "false" }))).toEqual({ ok: true, value: false });
    expect(parseSaleInput(form({ onSale: "yes" })).ok).toBe(false);
  });
});

describe("auditChanges", () => {
  const base = { name: "키보드", description: "", price: 39_000, onSale: true };
  const entry = (e: Partial<AuditEntry>): AuditEntry => ({
    action: "PRICE_CHANGED",
    before: base,
    after: base,
    changedBy: 1000,
    changedAt: "2026-11-25T00:00:00Z",
    ...e,
  });

  it("바뀐 값만 전 → 후", () => {
    expect(auditChanges(entry({ after: { ...base, price: 35_000 } }))).toEqual([
      { label: "가격", before: "39,000원", after: "35,000원" },
    ]);
    expect(
      auditChanges(entry({ action: "SALE_STATUS_CHANGED", after: { ...base, onSale: false } })),
    ).toEqual([{ label: "판매 상태", before: "판매 중", after: "판매 중지" }]);
  });

  it("등록은 처음 값 전부, 빈 설명은 (없음)", () => {
    expect(auditChanges(entry({ action: "REGISTERED", before: null }))).toEqual([
      { label: "이름", before: null, after: "키보드" },
      { label: "설명", before: null, after: "(없음)" },
      { label: "가격", before: null, after: "39,000원" },
      { label: "판매 상태", before: null, after: "판매 중" },
    ]);
  });

  it("옮겨 온 옛 가격 이력은 가격만", () => {
    const priceOnly = { name: null, description: null, price: 10_000, onSale: null };
    expect(
      auditChanges(entry({ before: priceOnly, after: { ...priceOnly, price: 12_000 } })),
    ).toEqual([{ label: "가격", before: "10,000원", after: "12,000원" }]);
  });
});
