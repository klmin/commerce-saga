import { createBrowserRouter, Navigate } from "react-router";

import { RegisterPage } from "@/pages/register-page";
import { routerBasename } from "@/lib/base";
import { SignInPage } from "@/pages/signin-page";

// 화면 경로는 /signin·/register 둘. 서버 경로(login·signup·csrf·oauth2 …)는 쓰지 않는다(설계 문서 7-1 경로 예약).
// 그 밖은 로그인 화면으로
export const router = createBrowserRouter(
  [
    { path: "/signin", element: <SignInPage /> },
    { path: "/register", element: <RegisterPage /> },
    { path: "*", element: <Navigate to="/signin" replace /> },
  ],
  { basename: routerBasename() },
);
