import { Environment } from './environment.model';

/**
 * Production - swapped in for `environment.ts` by the `fileReplacements` in
 * the `production` build configuration, so `ng build --configuration=production`
 * ships this and nothing else.
 *
 * <p>The API origin is the same host the app is served from, which means the
 * browser makes same-origin calls and the reverse proxy in front of this has
 * to route `/api` to the backend. CORS still allows the origin explicitly on
 * the backend side, so a split-host deployment only needs this constant and
 * `micro-grid.cors.allowed-origins` changed together.
 */
export const environment: Environment = {
  production: true,
  apiBaseUrl: 'https://sustainalens.com',
  recaptcha: {
    enabled: true,
    siteKey: '6LfElZMtAAAAANQqPQwuxrZiGEPrA525f1tmgQtA',
  },
};
