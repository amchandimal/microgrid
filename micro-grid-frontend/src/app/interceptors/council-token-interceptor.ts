import { inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';
import { Auth } from '../services/auth';
import { apiPathOf } from '../core/api';

/**
 * Attaches the council bearer token to council calls, and to nothing else.
 *
 * <p>Scoped to `/api/council/` on purpose: the public endpoints and the
 * Nominatim geocoder share this HttpClient, and a session token has no business
 * being sent to a third party. `apiPathOf` is what decides whose URL this is,
 * so the check keeps working now that the base URL comes from the environment
 * and is absolute in both configurations.
 */
export const councilTokenInterceptor: HttpInterceptorFn = (request, next) => {
  const token = inject(Auth).token();
  const path = apiPathOf(request.url);

  if (!token || !path?.startsWith('/api/council/')) {
    return next(request);
  }
  return next(
    request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }),
  );
};
