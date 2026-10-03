import { loadEnv } from "vite";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const apiTarget = env.VITE_API_PROXY_TARGET || "http://127.0.0.1:8080";

  return {
    plugins: [react()],
    test: {
      environment: "jsdom",
      setupFiles: ["./src/test/setup.ts"],
      clearMocks: true,
      coverage: {
        provider: "v8",
        reporter: ["text-summary", "lcov"],
        reportsDirectory: "coverage",
        include: ["src/**/*.{ts,tsx}"],
        exclude: ["src/**/*.test.{ts,tsx}", "src/test/**", "src/main.tsx"],
      },
    },
    server: {
      host: "127.0.0.1",
      port: 5173,
      strictPort: true,
      proxy: {
        "/api": {
          target: apiTarget,
          changeOrigin: false,
        },
      },
    },
    preview: {
      host: "127.0.0.1",
      port: 5173,
      strictPort: true,
    },
  };
});
