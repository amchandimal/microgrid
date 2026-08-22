import { apiPathOf, apiUrl, recaptchaActionFor } from './api';
import { environment } from '../../environments/environment';

/**
 * These run against the development environment - the one `ng test` compiles -
 * so `apiUrl` here produces the localhost:8080 form.
 */
describe('API URLs', () => {
  it('hangs paths off the configured origin', () => {
    expect(apiUrl('/api/council/summary')).toBe(
      `${environment.apiBaseUrl}/api/council/summary`,
    );
  });

  it('recognises its own API, absolute or relative', () => {
    expect(apiPathOf(apiUrl('/api/council/summary'))).toBe('/api/council/summary');
    expect(apiPathOf('/api/grid/region')).toBe('/api/grid/region');
  });

  /** Neither a session token nor a reCAPTCHA token may leave this way. */
  it('does not claim a third party', () => {
    expect(apiPathOf('https://nominatim.openstreetmap.org/search?q=/api/council/')).toBeNull();
    expect(apiPathOf('https://example.invalid/api/council/summary')).toBeNull();
  });

  it('does not claim non-API paths on its own origin', () => {
    expect(apiPathOf('/favicon.ico')).toBeNull();
    expect(apiPathOf('/apiary/thing')).toBeNull();
  });
});

/** The backend's RecaptchaActions.forPath has to agree with every one of these. */
describe('reCAPTCHA actions', () => {
  it('names the login on its own', () => {
    expect(recaptchaActionFor('/api/auth/login')).toBe('LOGIN');
  });

  it('otherwise uses the API area', () => {
    expect(recaptchaActionFor('/api/council/summary')).toBe('COUNCIL');
    expect(recaptchaActionFor('/api/council/suburbs/Port%20Kembla')).toBe('COUNCIL');
    expect(recaptchaActionFor('/api/grid/cells')).toBe('GRID');
    expect(recaptchaActionFor('/api/wizard/plan')).toBe('WIZARD');
    expect(recaptchaActionFor('/api/solar/2500')).toBe('SOLAR');
  });

  it('falls back when there is no area', () => {
    expect(recaptchaActionFor('/api')).toBe('API');
    expect(recaptchaActionFor('/api/')).toBe('API');
  });
});
