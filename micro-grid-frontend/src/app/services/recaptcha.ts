import { Injectable } from '@angular/core';
import { environment } from '../../environments/environment';

/** The slice of `grecaptcha.enterprise` this app uses. */
interface GreCaptchaEnterprise {
  ready(callback: () => void): void;
  execute(siteKey: string, options: { action: string }): Promise<string>;
}

declare global {
  interface Window {
    grecaptcha?: { enterprise: GreCaptchaEnterprise };
  }
}

/**
 * reCAPTCHA Enterprise, in production only.
 *
 * <p>The script tag is injected rather than sat in `index.html`, because the
 * point of the environment split is that a development build talks to nothing
 * of Google's: a tag in the head would load on `ng serve` too and mint tokens
 * against a site key registered to sustainalens.com.
 *
 * <p>`enterprise.js` is loaded once, lazily, on the first API call that needs
 * a token, and every call after that reuses the loaded script. Tokens
 * themselves are not reused - they expire after two minutes and the backend
 * spends each one on a single assessment - so `execute` runs per request.
 */
@Injectable({ providedIn: 'root' })
export class Recaptcha {
  /** False in development, and false in production without a site key. */
  readonly enabled =
    environment.recaptcha.enabled && environment.recaptcha.siteKey.length > 0;

  private loading?: Promise<GreCaptchaEnterprise>;

  /**
   * A fresh token for one action, or null when there is none to be had.
   *
   * <p>Null on failure rather than a rejection: a blocked or unreachable
   * enterprise.js should produce the backend's "failed reCAPTCHA" answer, the
   * same as a bad token would, instead of a second failure mode the UI would
   * have to describe separately.
   */
  async execute(action: string): Promise<string | null> {
    if (!this.enabled) {
      return null;
    }
    try {
      const enterprise = await this.ready();
      return await enterprise.execute(environment.recaptcha.siteKey, { action });
    } catch (error) {
      console.error('reCAPTCHA could not issue a token', error);
      return null;
    }
  }

  private ready(): Promise<GreCaptchaEnterprise> {
    // Clear the cache on failure so a later request can try the load again;
    // a rejected promise left in place would poison every call after it.
    this.loading ??= load(environment.recaptcha.siteKey).catch((error) => {
      this.loading = undefined;
      throw error;
    });
    return this.loading;
  }
}

/** Adds the enterprise.js tag and resolves once grecaptcha is usable. */
function load(siteKey: string): Promise<GreCaptchaEnterprise> {
  return new Promise((resolve, reject) => {
    const alreadyThere = window.grecaptcha;
    if (alreadyThere) {
      alreadyThere.enterprise.ready(() => resolve(alreadyThere.enterprise));
      return;
    }

    const tag = document.createElement('script');
    tag.src = `https://www.google.com/recaptcha/enterprise.js?render=${encodeURIComponent(siteKey)}`;
    tag.async = true;
    tag.defer = true;
    tag.onload = () => {
      const loaded = window.grecaptcha;
      if (!loaded) {
        reject(new Error('enterprise.js loaded without defining grecaptcha.'));
        return;
      }
      // ready() waits for the key's config to arrive, not just the script.
      loaded.enterprise.ready(() => resolve(loaded.enterprise));
    };
    tag.onerror = () =>
      reject(new Error('Could not load reCAPTCHA Enterprise.'));

    document.head.appendChild(tag);
  });
}
