import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/workspace.spec.js",
  timeout: 120000,
  expect: { timeout: 45000 },
  use: {
    baseURL: "http://localhost:5273",
    viewport: { width: 1440, height: 1000 },
    trace: "retain-on-failure",
  },
  webServer: {
    command: "npm run dev",
    url: "http://localhost:5273",
    reuseExistingServer: false,
    env: {
      VITE_SHELL_PORT: "5273",
      VITE_SHELL_ORIGIN: "http://localhost:5273",
      ...Object.fromEntries(
        ["transport", "fleet", "analytics", "tracking"].flatMap(
          (name, index) => {
            const port = 5274 + index;
            return [
              [`VITE_${name.toUpperCase()}_PORT`, String(port)],
              [
                `VITE_${name.toUpperCase()}_PUBLIC_URL`,
                `http://localhost:${port}`,
              ],
              [
                `VITE_${name.toUpperCase()}_REMOTE`,
                `http://localhost:${port}/remoteEntry.js`,
              ],
            ];
          },
        ),
      ),
    },
    timeout: 240000,
  },
});
