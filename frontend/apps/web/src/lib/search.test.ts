import { describe, expect, it } from "vitest";

import { parseSearch, searchQuery } from "./search";

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
