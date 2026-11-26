import { describe, expect, it } from "vitest";

import { deviceName } from "./device";

describe("deviceName", () => {
  it("브라우저 · OS", () => {
    expect(
      deviceName(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36 Edg/140.0",
      ),
    ).toBe("Edge · Windows");
    expect(
      deviceName(
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
      ),
    ).toBe("Safari · iOS");
    expect(
      deviceName(
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36",
      ),
    ).toBe("Chrome · Linux");
  });

  it("모르는 값은 앞부분만, 없으면 알 수 없는 기기", () => {
    expect(deviceName("commerce-saga-scripts")).toBe("commerce-saga-scripts");
    expect(deviceName("curl/8.10")).toBe("curl");
    expect(deviceName(null)).toBe("알 수 없는 기기");
    expect(deviceName("  ")).toBe("알 수 없는 기기");
  });
});
