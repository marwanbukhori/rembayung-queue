import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { keyInterceptor } from './key';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    // Every /api call carries the console key; without it the console answers
    // 401 before any handler runs.
    provideHttpClient(withFetch(), withInterceptors([keyInterceptor]))
  ]
};
