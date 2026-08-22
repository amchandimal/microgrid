import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Auth } from './services/auth';

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  standalone: false,
  styleUrl: './app.scss'
})
export class App {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);

  protected readonly title = signal('Micro-Grid');

  /** The nav shows the dashboard once signed in, the sign-in link otherwise. */
  protected isCouncil(): boolean {
    return this.auth.isCouncil();
  }

  protected signOut(): void {
    this.auth.logout();
    this.router.navigateByUrl('/');
  }
}
