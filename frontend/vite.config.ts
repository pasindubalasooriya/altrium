/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],

  server: {
    // Fixed, not incidental. This exact origin is registered as the redirect URL in the
    // Asgardeo console and allowed by the backend's CORS configuration, so a port that
    // drifts breaks login and every API call at once. strictPort makes that a startup
    // failure rather than a mystery in the browser.
    port: 5173,
    strictPort: true,
  },

  // Deliberately no dev proxy for /api. The backend allows this origin properly, so the
  // browser is doing real cross-origin calls in development - the same thing it will do
  // once deployed. A proxy would hide a CORS misconfiguration until the first deployment.

  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
})
