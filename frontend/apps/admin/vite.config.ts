import { fileURLToPath } from "node:url";

import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// 관리자 화면. 로컬 개발: 설계 문서 7-1 "화면 쪽" 경로를 Gateway(SCG)로 보낸다. compose·k8s 에서는 앞단 프록시(Caddy·Envoy)가 같은 일을 한다.
// changeOrigin false: 원래 Host(localhost:5174)를 넘겨야 Gateway 가 이 화면의 로그인 클라이언트·돌아올 주소를 고른다
const scg = { target: process.env.GATEWAY_URL ?? "http://localhost:8000", changeOrigin: false };
const proxy = {
  "/api": scg,
  "/oauth2/authorization": scg,
  "/login/oauth2/code": scg,
  "/logout": scg,
};

export default defineConfig({
  // 상대 경로로 빌드한다. 앞 경로(경로 방식의 /admin/ 등)는 빌드에 넣지 않고 실행할 때 <base href> 로 준다 (설계 문서 7-4)
  base: "./",
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  build: {
    // 화면 하나가 한 묶음(react·router·query·zod·radix). 나눌 만큼 크지 않다
    chunkSizeWarningLimit: 700,
  },
  server: { port: 5174, strictPort: true, proxy },
  // e2e 는 production 빌드를 preview 로 띄운다 (같은 프록시)
  preview: { port: 5174, strictPort: true, proxy },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
