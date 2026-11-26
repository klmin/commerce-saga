import { fileURLToPath } from "node:url";

import type { NextConfig } from "next";

// 5-5 까지 남기는 Next 화면 (ADR 0017). 상위 frontend/ 가 pnpm workspace(새 SPA)라 lockfile 이 위에도 있다.
// 루트를 이 폴더로 고정해 standalone 출력 경로(server.js)가 바뀌지 않게 한다
const root = fileURLToPath(new URL(".", import.meta.url));

const nextConfig: NextConfig = {
  // Docker 이미지용 (.next/standalone)
  output: "standalone",
  outputFileTracingRoot: root,
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    root,
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
