import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({
  plugins: [react()],
  // Local development only; production should reverse-proxy /api on same origin.
  server: { proxy: { '/api': 'http://localhost:8080' } }
});
