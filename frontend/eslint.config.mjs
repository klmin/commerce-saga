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
    // shadcn 컴포넌트는 variants 같은 값도 함께 내보낸다
    files: ["packages/ui/src/**/*.tsx"],
    rules: { "react-refresh/only-export-components": "off" },
  },
  {
    // 앱 진입점(라우터·QueryClient 를 만든다). 빠른 새로고침 대상이 아니다
    files: ["apps/*/src/main.tsx", "apps/*/src/app.tsx"],
    rules: { "react-refresh/only-export-components": "off" },
  },
  prettier,
]);
