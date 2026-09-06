import { Directive, ElementRef, OnDestroy, inject, input } from '@angular/core';

/**
 * Fades a block in as it reaches the viewport.
 *
 * An IntersectionObserver rather than a scroll listener: the browser decides
 * when the element is visible, so nothing runs on the scroll thread and a long
 * page costs nothing to scroll.
 *
 * It reveals once and then disconnects. A section that re-animates every time it
 * scrolls back into view draws attention to the animation instead of the
 * content, and on a page somebody is reading top to bottom it would fire on the
 * way back up.
 *
 * Everything is visible with no JavaScript and with reduced motion: the class
 * only adds a transition, so the element's resting state is the visible one.
 */
@Directive({
  selector: '[rbReveal]',
  host: { '[class.rb-reveal]': 'true', '[class.rb-shown]': 'shown' }
})
export class Reveal implements OnDestroy {
  /** Stagger, in ms, so a row of cards arrives one after another. */
  readonly rbReveal = input(0);

  protected shown = false;
  private observer?: IntersectionObserver;

  constructor() {
    const host = inject(ElementRef).nativeElement as HTMLElement;

    // No observer (or no motion wanted) means show it now rather than never.
    const wantsMotion = typeof matchMedia === 'function'
      && !matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (typeof IntersectionObserver === 'undefined' || !wantsMotion) {
      this.shown = true;
      return;
    }

    this.observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          setTimeout(() => (this.shown = true), this.rbReveal());
          this.observer?.disconnect();
        }
      }
      // 12% rather than any sliver, so a block starts arriving when it is
      // genuinely being looked at rather than when its top edge grazes the fold.
    }, { threshold: 0.12 });
    this.observer.observe(host);
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
  }
}
