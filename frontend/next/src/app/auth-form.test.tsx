import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { AuthFormState } from "./auth-actions";
import { AuthForm } from "./auth-form";

vi.mock("./auth-actions", () => ({}));

describe("AuthForm", () => {
  it("실패하면 오류를 알리고 아이디는 다시 채운다 (비밀번호는 비운다)", async () => {
    const action = vi.fn<(p: AuthFormState, f: FormData) => Promise<AuthFormState>>(async () => ({
      error: "아이디 또는 비밀번호가 올바르지 않습니다.",
      loginId: "user1",
    }));
    render(<AuthForm mode="login" action={action} next="/orders" />);

    await userEvent.type(screen.getByLabelText("아이디"), "user1");
    await userEvent.type(screen.getByLabelText("비밀번호"), "wrong-pass");
    await userEvent.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("올바르지 않습니다");
    expect(screen.getByLabelText("아이디")).toHaveValue("user1");
    const sent = action.mock.calls[0][1];
    expect(sent.get("next")).toBe("/orders");
    expect(sent.get("password")).toBe("wrong-pass");
  });

  it("회원가입은 비밀번호 확인 칸과 안내를 보여 준다", () => {
    render(<AuthForm mode="signup" action={vi.fn()} />);

    expect(screen.getByLabelText("비밀번호 확인")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "가입하기" })).toBeInTheDocument();
    expect(screen.getByText(/1,000P/)).toBeInTheDocument();
  });
});
