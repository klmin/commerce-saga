import { render, screen, waitFor } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { routerBasename } from "@/lib/base";
import { RegisterPage } from "./register-page";
import { SignInPage } from "./signin-page";

function renderAt(path: string) {
  const router = createMemoryRouter(
    [
      { path: "/signin", element: <SignInPage /> },
      { path: "/register", element: <RegisterPage /> },
    ],
    { initialEntries: [path] },
  );
  return render(<RouterProvider router={router} />);
}

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function csrfOk() {
  fetchMock.mockResolvedValue(
    new Response(
      JSON.stringify({ parameterName: "_csrf", headerName: "X-CSRF-TOKEN", token: "t-1" }),
      {
        status: 200,
        headers: { "Content-Type": "application/json" },
      },
    ),
  );
}

describe("로그인 화면", () => {
  it("CSRF 토큰을 받으면 숨은 필드에 넣고 상대 경로 login 으로 폼 POST 한다", async () => {
    csrfOk();
    const { container } = renderAt("/signin");
    const submit = screen.getByRole("button", { name: "로그인" });
    expect(submit).toBeDisabled();

    await waitFor(() => expect(submit).toBeEnabled());
    const form = container.querySelector("form")!;
    expect(form.getAttribute("method")).toBe("post");
    expect(form.getAttribute("action")).toBe("login");
    const hidden = form.querySelector<HTMLInputElement>('input[type="hidden"]')!;
    expect(hidden.name).toBe("_csrf");
    expect(hidden.value).toBe("t-1");
    expect(screen.getByLabelText("아이디")).toHaveAttribute("name", "username");
    expect(screen.getByLabelText("비밀번호")).toHaveAttribute("name", "password");
    // 상대 경로: <base href> 기준 (경로 방식이면 /auth/csrf)
    expect(fetchMock).toHaveBeenCalledWith(
      "csrf",
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it("실패·잠김 문구", async () => {
    csrfOk();
    renderAt("/signin?error");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "아이디 또는 비밀번호가 맞지 않습니다",
    );
  });

  it("잠기면 잠김 문구", async () => {
    csrfOk();
    renderAt("/signin?error=locked");
    expect(await screen.findByRole("alert")).toHaveTextContent("잠시 잠겼습니다");
  });

  it("CSRF 토큰을 못 받으면 보내지 않고 다시 시도할 수 있다", async () => {
    fetchMock.mockResolvedValueOnce(new Response("", { status: 503 }));
    renderAt("/signin");
    expect(await screen.findByText("로그인 서버에 연결하지 못했습니다.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "로그인" })).toBeDisabled();

    csrfOk();
    screen.getByRole("button", { name: "다시 시도" }).click();
    await waitFor(() => expect(screen.getByRole("button", { name: "로그인" })).toBeEnabled());
  });
});

describe("회원가입 화면", () => {
  it("상대 경로 signup 으로 폼 POST, 중복 아이디 문구", async () => {
    csrfOk();
    const { container } = renderAt("/register?error=duplicate");
    expect(await screen.findByRole("alert")).toHaveTextContent("이미 사용 중인 아이디입니다");
    expect(container.querySelector("form")!.getAttribute("action")).toBe("signup");
    expect(screen.getByLabelText("아이디")).toHaveAttribute("name", "loginId");
    expect(screen.getByRole("link", { name: "로그인" })).toHaveAttribute("href", "/signin");
  });
});

describe("routerBasename", () => {
  it("<base href> 경로에서 끝 / 를 뗀다", () => {
    expect(routerBasename("http://localhost/")).toBe("/");
    expect(routerBasename("http://example.com/auth/")).toBe("/auth");
  });
});
