import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.STAGING_BASE_URL;
if (!baseURL || !baseURL.startsWith('https://')) {
  // This configuration is loaded only by the explicit staging E2E command.
  throw new Error('STAGING_BASE_URL must be a real HTTPS staging origin');
}
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  use: {
    ...devices['Desktop Chrome'],
    baseURL,
    // JWTs, credentials and Keycloak forms must not be persisted in CI artifacts.
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    ignoreHTTPSErrors: false
  }
});
