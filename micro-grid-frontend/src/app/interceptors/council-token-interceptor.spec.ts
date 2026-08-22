import { TestBed } from '@angular/core/testing';
import { HttpRequest, HttpHandlerFn, HttpEvent } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { councilTokenInterceptor } from './council-token-interceptor';
import { Auth } from '../services/auth';

/** Stands in for a signed-in (or signed-out) session. */
class FakeAuth {
  constructor(private readonly value: string | null) {}
  token = () => this.value;
}

function sendThrough(url: string, token: string | null): HttpRequest<unknown> {
  return TestBed.runInInjectionContext(() => {
    let seen!: HttpRequest<unknown>;
    const next: HttpHandlerFn = (request): Observable<HttpEvent<unknown>> => {
      seen = request;
      return of({} as HttpEvent<unknown>);
    };
    councilTokenInterceptor(new HttpRequest('GET', url), next).subscribe();
    return seen;
  });
}

describe('Council token interceptor', () => {
  function configure(token: string | null) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [{ provide: Auth, useValue: new FakeAuth(token) }],
    });
  }

  it('attaches the bearer token to council calls', () => {
    configure('council-demo-token');
    const request = sendThrough('/api/council/summary', 'council-demo-token');
    expect(request.headers.get('Authorization')).toBe('Bearer council-demo-token');
  });

  it('leaves the public API alone', () => {
    configure('council-demo-token');
    for (const url of ['/api/grid/region', '/api/wizard/plan', '/api/auth/login']) {
      expect(sendThrough(url, 'council-demo-token').headers.get('Authorization')).toBeNull();
    }
  });

  /** A session token has no business going to a third party. */
  it('never sends the token off this origin', () => {
    configure('council-demo-token');
    const request = sendThrough(
      'https://nominatim.openstreetmap.org/search?q=/api/council/',
      'council-demo-token',
    );
    expect(request.headers.get('Authorization')).toBeNull();
  });

  it('sends nothing when nobody is signed in', () => {
    configure(null);
    expect(sendThrough('/api/council/summary', null).headers.get('Authorization')).toBeNull();
  });
});
