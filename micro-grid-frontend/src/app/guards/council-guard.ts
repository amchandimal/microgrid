import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from '../services/auth';

/**
 * Keeps `/council` behind the council login.
 *
 * <p>This is a courtesy, not the security boundary - the guard only decides
 * what the browser renders. The data itself is refused by the backend without
 * a bearer token, which is where the real check lives.
 */
export const councilGuard: CanActivateFn = (_route, state) => {
  const auth = inject(Auth);
  const router = inject(Router);

  if (auth.isCouncil()) {
    return true;
  }
  // Remember where they were headed so the login can send them back.
  return router.createUrlTree(['/login/council'], {
    queryParams: { next: state.url },
  });
};
