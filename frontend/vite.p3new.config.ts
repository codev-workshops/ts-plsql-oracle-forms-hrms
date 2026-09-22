import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
// integration-session harness: second Vite instance proxied to the employee=NEW backend (8082)
export default defineConfig({
  plugins: [react()],
  server: { port: 5174, proxy: { '/api': 'http://localhost:8082', '/legacy': 'http://localhost:8081' } },
});
