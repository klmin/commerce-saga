import { describe, expect, it } from "vitest";

import {
  type AuditEntry,
  auditChanges,
  checkImageFile,
  imageUrl,
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
    expect(
      parseDescribeInput(
        form({ name: " 키보드 ", description: " 무선 ", categoryCode: "digital" }),
      ),
    ).toEqual({
      ok: true,
      value: { name: "키보드", description: "무선", categoryCode: "digital" },
    });
    expect(parseDescribeInput(form({ name: "  ", categoryCode: "etc" })).ok).toBe(false);
    expect(parseDescribeInput(form({ name: "가".repeat(101), categoryCode: "etc" })).ok).toBe(
      false,
    );
    expect(
      parseDescribeInput(form({ name: "a", description: "가".repeat(501), categoryCode: "etc" }))
        .ok,
    ).toBe(false);
    // 카테고리는 골라야 하고 코드 모양이어야 한다
    expect(parseDescribeInput(form({ name: "a" })).ok).toBe(false);
    expect(parseDescribeInput(form({ name: "a", categoryCode: "../x" })).ok).toBe(false);
  });

  it("가격은 1원 ~ 1억 원 정수, 쉼표는 허용", () => {
    expect(parsePriceInput(form({ price: "35,000" }))).toEqual({ ok: true, value: 35_000 });
    expect(parsePriceInput(form({ price: "0" })).ok).toBe(false);
    expect(parsePriceInput(form({ price: "100000001" })).ok).toBe(false);
    expect(parsePriceInput(form({ price: "1.5" })).ok).toBe(false);
    expect(parsePriceInput(form({ price: "-1" })).ok).toBe(false);
  });

  it("등록은 이름·가격 둘 다", () => {
    expect(
      parseRegisterInput(form({ name: "허브", price: "18000", categoryCode: "digital" })),
    ).toEqual({
      ok: true,
      value: { name: "허브", description: "", categoryCode: "digital", price: 18_000 },
    });
    expect(parseRegisterInput(form({ name: "허브", categoryCode: "digital" })).ok).toBe(false);
  });

  it("판매 상태는 true/false 만", () => {
    expect(parseSaleInput(form({ onSale: "false" }))).toEqual({ ok: true, value: false });
    expect(parseSaleInput(form({ onSale: "yes" })).ok).toBe(false);
  });
});

describe("auditChanges", () => {
  const base = {
    name: "키보드",
    description: "",
    price: 39_000,
    onSale: true,
    categoryCode: "digital",
    imagePath: null,
  };
  const entry = (e: Partial<AuditEntry>): AuditEntry => ({
    action: "PRICE_CHANGED",
    before: base,
    after: base,
    changedBy: "01900000-0000-7000-8001-000000000100",
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
      { label: "카테고리", before: null, after: "digital" },
    ]);
  });

  it("옮겨 온 옛 가격 이력은 가격만", () => {
    const priceOnly = {
      name: null,
      description: null,
      price: 10_000,
      onSale: null,
      categoryCode: null,
      imagePath: null,
    };
    expect(
      auditChanges(entry({ before: priceOnly, after: { ...priceOnly, price: 12_000 } })),
    ).toEqual([{ label: "가격", before: "10,000원", after: "12,000원" }]);
  });
});

describe("auditChanges 카테고리·이미지 (ADR 0021)", () => {
  const base = {
    name: "키보드",
    description: "",
    price: 39_000,
    onSale: true,
    categoryCode: "etc",
    imagePath: null,
  };
  const names = new Map([
    ["digital", "디지털"],
    ["etc", "기타"],
  ]);
  const by = "01900000-0000-7000-8001-000000000100";
  const at = "2026-11-25T00:00:00Z";

  it("카테고리는 이름으로, 모르는 코드는 코드 그대로", () => {
    expect(
      auditChanges(
        {
          action: "DESCRIBED",
          before: base,
          after: { ...base, categoryCode: "digital" },
          changedBy: by,
          changedAt: at,
        },
        names,
      ),
    ).toEqual([{ label: "카테고리", before: "기타", after: "디지털" }]);
    expect(
      auditChanges(
        {
          action: "DESCRIBED",
          before: base,
          after: { ...base, categoryCode: "new-one" },
          changedBy: by,
          changedAt: at,
        },
        names,
      ),
    ).toEqual([{ label: "카테고리", before: "기타", after: "new-one" }]);
  });

  it("이미지 변경은 없던 것에서도 파일 이름으로", () => {
    const path = "/products/01900000-0000-7000-8000-000000000001/images/0193abcd.png";
    const first = auditChanges(
      {
        action: "IMAGE_CHANGED",
        before: base,
        after: { ...base, imagePath: path },
        changedBy: by,
        changedAt: at,
      },
      names,
    );
    expect(first).toEqual([{ label: "이미지", before: "(없음)", after: "0193abcd.png" }]);
  });
});

describe("checkImageFile", () => {
  const file = (size: number, type: string) => new File([new Uint8Array(size)], "a", { type });

  it("jpg·png·webp, 1바이트 ~ 2MB", () => {
    expect(checkImageFile(file(10, "image/png")).ok).toBe(true);
    expect(checkImageFile(file(2 * 1024 * 1024, "image/webp")).ok).toBe(true);
    expect(checkImageFile(file(2 * 1024 * 1024 + 1, "image/jpeg"))).toEqual({
      ok: false,
      error: "이미지는 2MB 까지 올릴 수 있습니다.",
    });
    expect(checkImageFile(file(10, "image/svg+xml")).ok).toBe(false);
    expect(checkImageFile(file(10, "image/gif")).ok).toBe(false);
    expect(checkImageFile(file(0, "image/png")).ok).toBe(false);
    expect(checkImageFile(null).ok).toBe(false);
  });
});

describe("imageUrl", () => {
  it("서비스 이미지 경로에 /api 를 붙이고, 다른 모양은 쓰지 않는다", () => {
    expect(imageUrl("/products/x/images/a.png")).toBe("/api/products/x/images/a.png");
    expect(imageUrl(null)).toBeUndefined();
    expect(imageUrl("https://evil.example/a.png")).toBeUndefined();
  });
});
