import { AfterViewInit, Directive, ElementRef, OnDestroy, inject, output } from '@angular/core';

/**
 * Put on the sentinel at the end of a list: says "more" when the reader scrolls
 * it into view.
 *
 * It watches against the nearest scrolling ancestor, so a list in its own
 * scroll box loads when that box reaches its end rather than when the page
 * does. It waits for the reader to scroll first, so a short list that fits
 * shows five and stays at five; clicking the sentinel works too, for a list
 * with nothing to scroll.
 */
@Directive({
  selector: '[rbLoadMore]',
  host: { '(click)': 'more.emit()', role: 'button', tabindex: '0', '(keydown.enter)': 'more.emit()' }
})
export class LoadMore implements AfterViewInit, OnDestroy {
  readonly more = output<void>();

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private observer?: IntersectionObserver;
  private frame = 0;
  private scrolled = false;
  private root: HTMLElement | Window = window;
  private readonly onScroll = () => {
    if (!this.scrolled) {
      this.scrolled = true;
      this.look();
    }
  };

  ngAfterViewInit(): void {
    const el = this.host.nativeElement;
    const root = scroller(el);
    this.root = root ?? window;
    this.root.addEventListener('scroll', this.onScroll, { passive: true });
    this.observer = new IntersectionObserver(entries => {
      if (!this.scrolled || !entries.some(e => e.isIntersecting)) {
        return;
      }
      this.more.emit();
      this.look();
    }, { root });
    this.observer.observe(el);
  }

  /** Observing again delivers a fresh reading once the new lines are drawn. */
  private look(): void {
    const el = this.host.nativeElement;
    cancelAnimationFrame(this.frame);
    this.frame = requestAnimationFrame(() => {
      this.observer?.unobserve(el);
      this.observer?.observe(el);
    });
  }

  ngOnDestroy(): void {
    this.root.removeEventListener('scroll', this.onScroll);
    cancelAnimationFrame(this.frame);
    this.observer?.disconnect();
  }
}

function scroller(el: HTMLElement): HTMLElement | null {
  for (let p = el.parentElement; p; p = p.parentElement) {
    const y = getComputedStyle(p).overflowY;
    if (y === 'auto' || y === 'scroll') {
      return p;
    }
  }
  return null;
}
