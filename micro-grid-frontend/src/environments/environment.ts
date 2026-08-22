import { Environment } from './environment.model';

/**
 * Development - what `ng serve` and `npm run start` use.
 *
 * <p>The API is the Spring Boot app on its own port, called cross-origin. The
 * backend allows `http://localhost:4200` under the `dev` profile
 * (`micro-grid.cors.allowed-origins`); serve the frontend on another port and
 * that property has to follow it.
 *
 * <p>reCAPTCHA is off here. Enterprise tokens are minted against a site key
 * registered to a domain, and scoring localhost traffic tells you nothing.
 */
export const environment: Environment = {
  production: false,
  apiBaseUrl: 'http://localhost:8080',
  recaptcha: {
    enabled: false,
    siteKey: '6LfElZMtAAAAANQqPQwuxrZiGEPrA525f1tmgQtA',
  },
};
