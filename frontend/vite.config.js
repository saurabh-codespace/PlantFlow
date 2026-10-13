import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { federation } from "@module-federation/vite";
import { shared } from "./federation.config";
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const proxy = Object.fromEntries(
    [
      ["trips", 8082],
      ["shuttles", 8082],
      ["locations", 8082],
      ["vehicles", 8083],
      ["drivers", 8083],
      ["analytics", 8084],
      ["events", 8084],
      ["tracking", 8085],
    ].map(([path, port]) => [
      `/api/${path}`,
      { target: `http://localhost:${port}`, changeOrigin: true },
    ]),
  );
  const remotes = Object.fromEntries(
    ["transport", "fleet", "analytics", "tracking"].map((name, index) => [
      name,
      {
        type: "module",
        name: `plantflow_${name}`,
        entry:
          env[`VITE_${name.toUpperCase()}_REMOTE`] ||
          `http://localhost:${5174 + index}/remoteEntry.js`,
      },
    ]),
  );
  return {
    cacheDir: "node_modules/.vite/shell",
    plugins: [
      react(),
      federation({
        name: "plantflow_shell",
        shareStrategy: "loaded-first",
        remotes,
        shared,
        moduleParseIdleTimeout: 60000,
        dts: false,
      }),
    ],
    server: {
      host: "127.0.0.1",
      port: Number(env.VITE_SHELL_PORT || 5173),
      strictPort: true,
      proxy,
    },
    preview: {
      host: "127.0.0.1",
      port: Number(env.VITE_SHELL_PORT || 5173),
      strictPort: true,
      proxy,
    },
    build: { outDir: "dist/shell", target: "esnext" },
    test: { include: ["src/**/*.test.js", "microfrontends/**/*.test.js"] },
  };
});
