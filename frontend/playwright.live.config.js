import { defineConfig } from "@playwright/test";
import config from "./playwright.config";
export default defineConfig({
  ...config,
  testMatch: "**/live-stack.spec.js",
  timeout: 180000,
  expect: { timeout: 40000 },
  workers: 1,
  use: { ...config.use, trace: "retain-on-failure" },
});
