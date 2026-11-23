import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Must match the redirect URI registered for the "erp-web" client in Keycloak.
    // Stop the web container first (docker compose stop web): it holds the same port.
    port: 5780,
    strictPort: true,
    // Same origin in development too: everything dynamic goes to the API (published on
    // 127.0.0.1:8780 by Docker Compose). In the Docker image, nginx does this (nginx.conf).
    proxy: {
      "/api": "http://localhost:8780",
      "/oauth2": "http://localhost:8780",
      "/login/oauth2": "http://localhost:8780",
      "/logout": "http://localhost:8780",
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
  },
});
