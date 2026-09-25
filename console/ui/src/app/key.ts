import { HttpInterceptorFn } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';

/**
 * The console key: read from the link once, remembered for the tab, and sent
 * as a header on every /api call.
 *
 * The owner sends a link, so the key arrives in the query string. It is kept in
 * sessionStorage rather than localStorage so it does not outlive the tab, and
 * sent as X-Console-Key rather than as a query parameter on each request so it
 * does not end up in the console's own access log line for every poll — twice a
 * second, forever.
 */
const STORAGE_KEY = 'rembayung.console.key';

export function consoleKey(): string | null {
  const fromLink = new URLSearchParams(window.location.search).get('key');
  if (fromLink) {
    remember(fromLink);
    return fromLink;
  }
  try {
    return window.sessionStorage.getItem(STORAGE_KEY);
  } catch {
    // Storage can be refused outright (private mode, a blocked third-party
    // context). The link still works; only the memory of it is lost.
    return null;
  }
}

export function hasConsoleKey(): boolean {
  return consoleKey() !== null;
}

function remember(key: string): void {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, key);
  } catch {
    // See above: a page that cannot remember the key still works with it.
  }
}

/**
 * Every request to the console's own API carries the key. Nothing else does —
 * this page only ever talks to its own origin, but sending a credential on a
 * request that does not need it is how credentials end up somewhere they should
 * not be.
 */
export const keyInterceptor: HttpInterceptorFn = (request, next) => {
  const key = consoleKey();
  if (!key || !request.url.startsWith('/api')) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { 'X-Console-Key': key } }));
};

/**
 * The shared demo key, for visitors who arrived without one. Fetched once:
 * shareable() is true only if the console answered /api/demo-key, which it
 * does while CONSOLE_SHARE_KEY is on. open() reloads the page with the key in
 * the URL, which stores it exactly as a sent link would.
 */
@Injectable({ providedIn: 'root' })
export class DemoKeyService {
  readonly shareable = signal(false);
  private key: string | null = null;

  constructor() {
    if (hasConsoleKey()) {
      return;
    }
    fetch('/api/demo-key')
      .then((r) => (r.ok ? r.json() : null))
      .then((body) => {
        this.key = body?.key ?? null;
        this.shareable.set(!!this.key);
      })
      .catch(() => this.shareable.set(false));
  }

  open(): void {
    if (!this.key) {
      return;
    }
    const url = new URL(window.location.href);
    url.searchParams.set('key', this.key);
    window.location.assign(url.toString());
  }
}
