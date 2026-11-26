import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { deviceName } from "@/lib/devices";
import { DevicesPage } from "./devices-page";

const fetchMock = vi.fn();
const assign = vi.fn();

const laptop = {
  id: "d-laptop",
  current: true,
  userAgent: "Mozilla/5.0 (Windows NT 10.0) Chrome/140.0 Safari/537.36",
  loginAt: "2026-11-26T01:00:00Z",
  lastSeenAt: "2026-11-26T02:00:00Z",
};
const phone = {
  id: "d-phone",
  current: false,
  userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Safari/604.1",
  loginAt: "2026-11-25T01:00:00Z",
  lastSeenAt: "2026-11-25T03:00:00Z",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

beforeEach(() => {
  fetchMock.mockReset();
  assign.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("로그인 기기 화면", () => {
  it("이 기기를 표시하고 다른 기기는 CSRF 헤더로 로그아웃한다", async () => {
    let devices = [laptop, phone];
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === "sessions" && !init?.method) return json(devices);
      if (url === "csrf")
        return json({ parameterName: "_csrf", headerName: "X-CSRF-TOKEN", token: "t-9" });
      if (url === "sessions/d-phone" && init?.method === "DELETE") {
        devices = [laptop];
        return new Response(null, { status: 204 });
      }
      return new Response(null, { status: 500 });
    });

    render(<DevicesPage goTo={assign} />);
    const list = await screen.findByRole("list", { name: "로그인 기기" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(2);
    expect(within(list).getByText("이 기기")).toBeInTheDocument();
    expect(within(list).getByText("Chrome · Windows")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Safari · iOS 로그아웃" }));

    await waitFor(() => expect(within(list).getAllByRole("listitem")).toHaveLength(1));
    const del = fetchMock.mock.calls.find(([, init]) => init?.method === "DELETE")!;
    expect(del[0]).toBe("sessions/d-phone");
    expect(del[1].headers).toEqual({ "X-CSRF-TOKEN": "t-9" });
    // 다른 기기가 없으면 "모두 로그아웃" 버튼도 없다
    expect(screen.queryByRole("button", { name: "다른 기기 모두 로그아웃" })).toBeNull();
  });

  it("로그인하지 않았으면(401) 로그인을 거쳐 돌아오는 주소로 간다", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
    render(<DevicesPage goTo={assign} />);
    await waitFor(() => expect(assign).toHaveBeenCalledWith("sessions/login"));
  });

  it("불러오기에 실패하면 다시 시도할 수 있다", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 503 }));
    render(<DevicesPage goTo={assign} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("503");
    fetchMock.mockResolvedValueOnce(json([laptop]));
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("이 기기")).toBeInTheDocument();
  });
});

describe("deviceName", () => {
  it("브라우저 문자열을 짧은 이름으로", () => {
    expect(deviceName(laptop.userAgent)).toBe("Chrome · Windows");
    expect(deviceName(phone.userAgent)).toBe("Safari · iOS");
    expect(
      deviceName("Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"),
    ).toBe("Firefox · Linux");
    expect(deviceName("curl/8.11.1")).toBe("curl");
    expect(deviceName("")).toBe("알 수 없는 브라우저");
  });
});
