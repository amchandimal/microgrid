import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { LoginResponse } from '../models/council.models';

/** What we keep about a signed-in session. */
interface Session {
  token: string;
  role: string;
  displayName: string;
}

const STORAGE_KEY = 'micro-grid.council-session';

/**
 * Demo authentication.
 *
 * <p>One fixed account, checked by the backend, which hands back a fixed token.
 * There is no refresh, no expiry and no user directory - the login exists
 * because suburb-level disadvantage rankings are not public data, not because
 * there are accounts to manage.
 *
 * <p>The session is mirrored into localStorage so a reload does not sign the
 * user out mid-demo. That is the right trade for a token that grants read
 * access to one dashboard; it would not be for a real credential.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly http = inject(HttpClient);

  private readonly session = signal<Session | null>(restore());

  readonly displayName = computed(() => this.session()?.displayName ?? null);
  readonly token = computed(() => this.session()?.token ?? null);

  isCouncil(): boolean {
    return this.session()?.role === 'COUNCIL';
  }

  login(username: string, password: string): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>('/api/auth/login', { username, password })
      .pipe(tap((response) => this.remember(response)));
  }

  logout(): void {
    this.session.set(null);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Private browsing can refuse storage; the in-memory signal still cleared.
    }
  }

  private remember(response: LoginResponse): void {
    const session: Session = {
      token: response.token,
      role: response.role,
      displayName: response.displayName,
    };
    this.session.set(session);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    } catch {
      // Not fatal - the session just will not survive a reload.
    }
  }
}

/** Reads back a stored session, ignoring anything that is not one. */
function restore(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<Session>;
    if (!parsed?.token || !parsed?.role) return null;
    return {
      token: parsed.token,
      role: parsed.role,
      displayName: parsed.displayName ?? 'Council',
    };
  } catch {
    return null;
  }
}
