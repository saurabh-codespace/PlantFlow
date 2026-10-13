import { defineConfig, loadEnv } from "vite";
import { federation } from "@module-federation/vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
const frontend = fileURLToPath(new URL(".", import.meta.url));
export const shared = {
  react: { singleton: true, eager: true },
  "react-dom": { singleton: true, eager: true },
  "react/jsx-runtime": { singleton: true, eager: true },
};
export function remoteConfig(name, defaultPort) {
  return defineConfig(({ mode }) => {
    const env = loadEnv(mode, frontend, "");
    const port = Number(env[`VITE_${name.toUpperCase()}_PORT`] || defaultPort);
    const origin =
      env[`VITE_${name.toUpperCase()}_PUBLIC_URL`] ||
      `http://localhost:${port}`;
    return {
      root: `${frontend}/microfrontends/${name}`,
      cacheDir: `${frontend}/node_modules/.vite/${name}`,
      envDir: frontend,
      optimizeDeps: {
        entries: [`${frontend}/microfrontends/${name}/src/App.jsx`],
        include: [
          "lucide-react",
          "@radix-ui/react-dialog",
          ...(name === "analytics" ? ["recharts", "motion/react"] : []),
          ...(name === "tracking" ? ["react-leaflet", "leaflet"] : []),
        ],
      },
      base: `${origin.replace(/\/$/, "")}/`,
      plugins: [
        react(),
        federation({
          name: `plantflow_${name}`,
          shareStrategy: "loaded-first",
          filename: "remoteEntry.js",
          exposes: {
            "./App": `${frontend}/microfrontends/${name}/src/App.jsx`,
          },
          shared,
          moduleParseIdleTimeout: 60000,
          dts: false,
        }),
      ],
      server: {
        host: "127.0.0.1",
        origin,
        port,
        strictPort: true,
        cors: { origin: env.VITE_SHELL_ORIGIN || "http://localhost:5173" },
        fs: { allow: [frontend] },
      },
      preview: {
        host: "127.0.0.1",
        port,
        strictPort: true,
        cors: { origin: env.VITE_SHELL_ORIGIN || "http://localhost:5173" },
      },
      build: {
        outDir: `${frontend}/dist/${name}`,
        emptyOutDir: true,
        target: "esnext",
      },
    };
  });
}
