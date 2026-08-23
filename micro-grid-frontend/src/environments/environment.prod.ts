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
  // OFF for now, to match the backend: the deployment has no Google Cloud API
  // key, so an assessment of any token we minted would be refused and every
  // call would 403. While this is false nothing loads enterprise.js and no
  // token header is sent.
  //
  // Turning it back on means a rebuild - this is a compile-time constant, not
  // an environment variable - and it has to happen in the same deployment that
  // sets RECAPTCHA_ENABLED=true on the backend. See application-prod.properties.
  recaptcha: {
    enabled: false,
    siteKey: '6LfElZMtAAAAANQqPQwuxrZiGEPrA525f1tmgQtA',
  },
};
