import { inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';
import { Auth } from '../services/auth';

/**
 * Attaches the council bearer token to council calls, and to nothing else.
 *
 * <p>Scoped to `/api/council/` on purpose: the public endpoints and the
 * Nominatim geocoder share this HttpClient, and a session token has no business
 * being sent to a third party.
 */
export const councilTokenInterceptor: HttpInterceptorFn = (request, next) => {
  const token = inject(Auth).token();

  if (!token || !isCouncilCall(request.url)) {
    return next(request);
  }
  return next(
    request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }),
  );
};

/** Matches the council API whether the URL is relative or absolute. */
function isCouncilCall(url: string): boolean {
  if (url.startsWith('/api/council/')) {
    return true;
  }
  try {
    const parsed = new URL(url, window.location.origin);
    return (
      parsed.origin === window.location.origin &&
      parsed.pathname.startsWith('/api/council/')
    );
  } catch {
    return false;
  }
}
