import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, of } from 'rxjs';
import { Auth } from '../../services/auth';

/**
 * Sign-in for the council view.
 *
 * <p>Deliberately the plainest form in the app. It exists so the suburb-level
 * rankings are not on the open web, and it gets out of the way in two fields.
 */
@Component({
  selector: 'app-council-login',
  standalone: false,
  templateUrl: './council-login.html',
  styleUrl: './council-login.scss',
})
export class CouncilLogin {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly signingIn = signal(false);
  protected readonly error = signal<string | null>(null);

  protected get canSubmit(): boolean {
    return (
      this.username().trim().length > 0 &&
      this.password().length > 0 &&
      !this.signingIn()
    );
  }

  protected submit(event: Event): void {
    event.preventDefault();
    if (!this.canSubmit) return;

    this.signingIn.set(true);
    this.error.set(null);

    this.auth
      .login(this.username().trim(), this.password())
      .pipe(
        catchError((e) => {
          this.error.set(
            e?.status === 401
              ? 'That username and password do not match a council account.'
              : 'Could not reach the sign-in service. Is the API running?',
          );
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((session) => {
        this.signingIn.set(false);
        if (!session) return;
        const next = this.route.snapshot.queryParamMap.get('next');
        this.router.navigateByUrl(next && next.startsWith('/') ? next : '/council');
      });
  }
}
