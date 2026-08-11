import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'node:url'
import { ignoreViteRuntimeWatchFile } from './src/utils/vite-watch-ignore.js'

// https://vite.dev/config/
export default defineConfig(({ command }) => ({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: command === 'build'
      ? [{
          find: /^\.\/ai-demo$/,
          replacement: fileURLToPath(new URL('./src/api/ai-demo-production-disabled.ts', import.meta.url)),
        }]
      : [],
  },
  build: {
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            {
              name: 'echarts-vendor',
              test: /node_modules[\\/](echarts|zrender|vue-echarts)[\\/]/,
              priority: 4,
              maxSize: 480_000,
              includeDependenciesRecursively: true,
            },
            {
              name: 'ant-vendor',
              test: /node_modules[\\/](ant-design-vue|@ant-design)[\\/]/,
              priority: 3,
              maxSize: 480_000,
              includeDependenciesRecursively: true,
            },
            {
              name: 'vue-vendor',
              test: /node_modules[\\/](vue|vue-router|pinia|@vueuse)[\\/]/,
              priority: 2,
              maxSize: 420_000,
              includeDependenciesRecursively: true,
            },
            {
              name: 'vendor',
              test: /node_modules/,
              priority: 1,
              maxSize: 480_000,
              includeDependenciesRecursively: true,
            },
          ],
        },
      },
    },
  },
  server: {
    hmr: process.env.VITE_E2E_DISABLE_HMR === 'true' ? false : undefined,
    watch: {
      ignored: ignoreViteRuntimeWatchFile,
    },
    proxy: {
      '/api': {
        target: process.env.VITE_BACKEND_PROXY_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: false,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq, req) => {
            const origin = req.headers.origin
            if (typeof origin === 'string' && origin) proxyReq.setHeader('Origin', origin)
            const referer = req.headers.referer
            if (typeof referer === 'string' && referer) proxyReq.setHeader('Referer', referer)
          })
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      include: [
        'src/api/**/*.ts',
        'src/stores/**/*.ts',
        'src/utils/**/*.ts',
      ],
      exclude: ['src/**/*.test.ts'],
      thresholds: {
        statements: 80,
        branches: 80,
        functions: 80,
        lines: 80,
      },
    },
  },
}))
