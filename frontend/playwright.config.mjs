import { defineConfig } from '@playwright/test'

// 浏览器回归独立于代理测试；产物写入已忽略的缓存目录。
export default defineConfig({
  testDir: './e2e/browser',
  testMatch: '**/*.spec.mjs',
  outputDir: './node_modules/.cache/browser-tests',
  fullyParallel: true,
  workers: 2,
  forbidOnly: true,
  retries: 0,
  timeout: 30000,
  reporter: 'list',
  use: { browserName: 'chromium', locale: 'en-US', viewport: { width: 1440, height: 1000 } }
})
