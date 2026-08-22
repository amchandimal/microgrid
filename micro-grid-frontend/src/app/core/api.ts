import { environment } from '../../environments/environment';

/** The configured API origin, with any trailing slashes taken off. */
const base = environment.apiBaseUrl.replace(/\/+$/, '');

/**
 * Absolute URL for an API path.
 *
 * @param path a path beginning with `/api`, e.g. `/api/council/summary`
 */
export function apiUrl(path: string): string {
  return `${base}${path}`;
}

/**
 * The `/api/...` path this URL targets, or null when it is not our API.
 *
 * <p>Both interceptors ask this rather than looking at the raw URL, because
 * after the environment split the same call is an absolute
 * `http://localhost:8080/api/...` in development and a same-origin
 * `https://sustainalens.com/api/...` in production. Anything else - Nominatim
 * above all - has to come back null: neither the council session token nor a
 * reCAPTCHA token should ever leave for a third party.
 */
export function apiPathOf(url: string): string | null {
  const page = window.location.origin;
  let target: URL;
  try {
    target = new URL(url, page);
  } catch {
    return null;
  }
  if (!target.pathname.startsWith('/api/')) {
    return null;
  }
  // The configured origin, plus the page's own - a relative "/api/..." is
  // still ours when the app and the API share a host.
  const apiOrigin = base ? new URL(base, page).origin : page;
  return target.origin === apiOrigin || target.origin === page
    ? target.pathname
    : null;
}

/**
 * The reCAPTCHA Enterprise action to score an API call under.
 *
 * <p>Deliberately coarse - one action per API area, plus LOGIN for the one
 * call that is worth watching on its own. Actions are what the reCAPTCHA
 * console groups scores by, so a per-URL action (with locality names in it)
 * would give thousands of one-request buckets and no signal.
 *
 * <p>`RecaptchaActions.forPath` on the backend derives the expected action the
 * same way and refuses a token that carries a different one. The two must
 * change together.
 */
export function recaptchaActionFor(path: string): string {
  if (path === '/api/auth/login') {
    return 'LOGIN';
  }
  // "/api/council/suburbs" -> ["", "api", "council", "suburbs"]
  const area = path.split('/')[2] ?? '';
  return area ? area.toUpperCase().replace(/[^A-Z0-9_-]/g, '_') : 'API';
}
