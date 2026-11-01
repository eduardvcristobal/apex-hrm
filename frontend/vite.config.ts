import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backendEnv = loadEnv(mode, resolve(process.cwd(), '../backend'), 'GOOGLE_CLIENT_ID');
  const googleClientId = env.VITE_GOOGLE_CLIENT_ID || backendEnv.GOOGLE_CLIENT_ID || '';

  return {
    plugins: [react()],
    define: {
      'import.meta.env.VITE_GOOGLE_CLIENT_ID': JSON.stringify(googleClientId),
    },
    server: {
      host: '127.0.0.1',
      port: Number(env.FRONTEND_PORT || 5173),
      strictPort: true,
      proxy: {
        '/api': {
          target: env.BACKEND_URL || `http://localhost:${env.BACKEND_PORT || '8080'}`,
          changeOrigin: true,
        },
      },
    },
  };
});
