/**
 * The shape both environment files must satisfy.
 *
 * <p>Declared once so the production file cannot drift from the development
 * one: `ng build --configuration=production` swaps the file, not the type, and
 * a key added to one and forgotten in the other is a compile error rather than
 * an `undefined` discovered in the browser.
 */
export interface Environment {
  readonly production: boolean;

  /**
   * Origin of the Spring Boot API, with no trailing slash and no `/api`
   * suffix - the services append their own paths. Absolute on purpose: the
   * dev server no longer proxies, so there is exactly one place that says
   * where the API is.
   */
  readonly apiBaseUrl: string;

  readonly recaptcha: {
    /**
     * Off outside production. When off nothing loads enterprise.js and no
     * token header is sent, which matches a backend whose reCAPTCHA
     * interceptor is only registered under the `prod` profile.
     */
    readonly enabled: boolean;
    /** reCAPTCHA Enterprise site key, from the Google Cloud console. */
    readonly siteKey: string;
  };
}
