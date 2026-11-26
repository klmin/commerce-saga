import { describe, expect, it } from "vitest";

import { basePath, routerBasename } from "./base";

describe("앞 경로", () => {
  it("호스트 방식·로컬 개발은 루트", () => {
    expect(basePath("http://localhost:5173/")).toBe("/");
    expect(routerBasename("http://localhost:5173/")).toBe("/");
  });

  it("경로 방식은 <base href> 의 경로 (/ 로 끝남), 라우터는 끝 / 를 뺀다", () => {
    expect(basePath("http://1.2.3.4/admin/")).toBe("/admin/");
    expect(routerBasename("http://1.2.3.4/admin/")).toBe("/admin");
  });
});
