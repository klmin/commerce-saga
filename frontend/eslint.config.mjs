import js from "@eslint/js";
import prettier from "eslint-config-prettier/flat";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import { defineConfig, globalIgnores } from "eslint/config";
import globals from "globals";
import tseslint from "typescript-eslint";

// workspace(apps/*, packages/*) 공통. next/ 는 자기 설정(eslint-config-next)을 쓴다
export default defineConfig([
  globalIgnores(["next/**", "**/dist/**", "**/playwright-report/**", "**/test-results/**"]),
  {
    files: ["**/*.{ts,tsx}"],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: { globals: globals.browser },
    rules: {
      "@typescript-eslint/no-unused-vars": [
        "warn",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
    },
  },
  {
    // 공용 패키지는 컴포넌트와 함께 값·훅도 내보낸다(shadcn variants, 세션 훅). 앱의 빠른 새로고침 경계가 아니다
    files: ["packages/*/src/**/*.tsx"],
    rules: { "react-refresh/only-export-components": "off" },
  },
  {
    // 앱 진입점(라우터·QueryClient 를 만든다). 빠른 새로고침 대상이 아니다
    files: ["apps/*/src/main.tsx", "apps/*/src/app.tsx"],
    rules: { "react-refresh/only-export-components": "off" },
  },
  prettier,
]);
