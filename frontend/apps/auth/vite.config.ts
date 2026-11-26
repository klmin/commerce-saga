import { fileURLToPath } from "node:url";

import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// 로컬 개발: 설계 문서 7-1 "인증 쪽" 의 auth-service 경로를 auth-service 로 보낸다. 그 밖(/signin·/register·정적 파일)은 이 화면.
// compose·k8s 에서는 앞단 프록시(Caddy·Envoy)가 같은 일을 한다.
// changeOrigin false: 원래 Host(localhost:5175)를 넘겨야 SAS 가 만드는 주소(로그인 화면 /signin, 인가 요청으로 돌아가기)가 이 화면 쪽이 된다
const sas = {
  target: process.env.AUTH_SERVICE_URL ?? "http://localhost:8086",
  changeOrigin: false,
};
const proxy = Object.fromEntries(
  [
    "/oauth2",
    "/.well-known",
    "/userinfo",
    "/connect/logout",
    "/login",
    "/signup",
    "/csrf",
    "/sessions",
  ].map((path) => [path, sas]),
);

export default defineConfig({
  // 상대 경로로 빌드한다. 앞 경로(경로 방식의 /auth/)는 빌드에 넣지 않고 실행할 때 <base href> 로 준다 (설계 문서 7-4)
  base: "./",
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  server: { port: 5175, strictPort: true, proxy },
  // e2e 는 production 빌드를 preview 로 띄운다 (같은 프록시)
  preview: { port: 5175, strictPort: true, proxy },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
