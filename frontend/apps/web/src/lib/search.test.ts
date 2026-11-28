import { describe, expect, it } from "vitest";

import { categoryUrl, headerSearchUrl, parseSearch, searchQuery } from "./search";

describe("parseSearch", () => {
  it("조건이 없으면 null (전체 목록)", () => {
    expect(parseSearch({})).toBeNull();
    expect(parseSearch({ q: "  ", sort: "relevance" })).toBeNull();
  });

  it("검색어를 다듬고 정렬·가격을 읽는다", () => {
    expect(
      parseSearch({ q: " 마우 ", minPrice: "1000", maxPrice: "30000", sort: "price-asc" }),
    ).toEqual({
      q: "마우",
      minPrice: 1000,
      maxPrice: 30000,
      sort: "price-asc",
    });
  });

  it("잘못된 값은 버리고, 뒤바뀐 가격 범위는 바로잡는다", () => {
    expect(parseSearch({ q: "a", minPrice: "-5", maxPrice: "abc", sort: "cheap" })).toEqual({
      q: "a",
      minPrice: undefined,
      maxPrice: undefined,
      sort: "relevance",
    });
    expect(parseSearch({ minPrice: "5000", maxPrice: "1000" })).toMatchObject({
      minPrice: 1000,
      maxPrice: 5000,
    });
    expect(parseSearch({ q: "가".repeat(150) })?.q).toHaveLength(100);
  });

  it("정렬만 바꿔도 검색으로 본다", () => {
    expect(parseSearch({ sort: "newest" })).toEqual({ sort: "newest" });
  });
});

describe("searchQuery", () => {
  it("있는 조건만 넣는다", () => {
    expect(searchQuery({ q: "키보드", sort: "relevance" }, 50)).toBe(
      "q=%ED%82%A4%EB%B3%B4%EB%93%9C&sort=relevance&size=50",
    );
    expect(searchQuery({ minPrice: 0, maxPrice: 100, sort: "newest" }, 10)).toBe(
      "minPrice=0&maxPrice=100&sort=newest&size=10",
    );
  });
});

describe("카테고리 (ADR 0021)", () => {
  it("코드 모양만 받고, 카테고리만 골라도 검색으로 본다", () => {
    expect(parseSearch({ category: "digital" })).toEqual({
      category: "digital",
      sort: "relevance",
    });
    expect(parseSearch({ category: "../x" })).toBeNull();
    expect(parseSearch({ category: "Digital" })).toBeNull();
    expect(searchQuery({ q: "a", category: "kitchen", sort: "relevance" }, 10)).toBe(
      "q=a&category=kitchen&sort=relevance&size=10",
    );
  });

  it("헤더 검색은 검색어를 새로 정하고 카테고리는 유지, 가격·정렬은 비운다", () => {
    const current = new URLSearchParams("q=old&category=digital&minPrice=1&sort=newest");
    expect(headerSearchUrl(" 마우 ", current)).toBe("/?q=%EB%A7%88%EC%9A%B0&category=digital");
    expect(headerSearchUrl("  ", current)).toBe("/?category=digital");
    expect(headerSearchUrl("", new URLSearchParams())).toBe("/");
    expect(headerSearchUrl("a", new URLSearchParams("category=../x"))).toBe("/?q=a");
  });

  it("카테고리 메뉴는 검색어를 유지하고 가격·정렬은 비운다, 전체는 카테고리만 뺀다", () => {
    const current = new URLSearchParams("q=a&category=digital&maxPrice=9&sort=newest");
    expect(categoryUrl("kitchen", current)).toBe("/?q=a&category=kitchen");
    expect(categoryUrl(undefined, current)).toBe("/?q=a");
    expect(categoryUrl(undefined, new URLSearchParams())).toBe("/");
  });
});
