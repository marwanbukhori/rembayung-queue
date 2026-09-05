import { DatePipe } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { LatencyStrip } from './latency-strip';
import { TrafficService } from './traffic.service';

/**
 * The rush as it happens, one line at a time.
 *
 * The counters beside this say where things stand; a queue is a thing that
 * moves, and a number that changes while you are not looking does not show
 * movement. This is the same reading rendered as what changed, so a visitor can
 * watch forty people arrive, then one get through, then a seat go.
 *
 * Newest at the top and capped, so it never grows without bound and never has
 * to be scrolled to see the thing that just happened.
 */
@Component({
  selector: 'rb-traffic-log',
  imports: [DatePipe, LatencyStrip],
  template: `
    <div class="card">
      <div class="head">
        <div>
          <div class="title">Live traffic</div>
          <p class="sub">Every change since the last read, two seconds apart</p>
        </div>
        <span class="status" [class.on]="traffic.flowing()">
          <span class="dot"></span>{{ traffic.flowing() ? 'moving' : 'idle' }}
        </span>
      </div>

      <rb-latency-strip />

      @if (feed().length) {
        <ol class="lines">
          @for (event of feed(); track event.seq) {
            <li class="line" [class]="event.kind">
              <span class="at mono">{{ event.at | date: 'HH:mm:ss' }}</span>
              <span class="tag mono">{{ label(event.kind) }}</span>
              <span class="text">{{ event.text }}</span>
            </li>
          }
        </ol>
      } @else {
        <p class="empty">
          Nothing has happened yet. Send a crowd and the arrivals, admissions and
          seats will appear here as they occur.
        </p>
      }
    </div>
  `,
  styles: `
    .card {
      background: var(--white);
      border: 1px solid var(--line);
      border-radius: 4px;
      overflow: hidden;
    }
    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: 8px 16px;
      padding: 16px;
      border-bottom: 1px solid var(--line);
    }
    .title { font-size: 19px; font-weight: 700; }
    .sub { margin: 2px 0 0; font-size: 14px; color: var(--muted); }

    .status {
      display: inline-flex;
      align-items: center;
      gap: 7px;
      font-family: var(--mono);
      font-size: 12px;
      color: var(--muted);
      white-space: nowrap;
    }
    .status .dot {
      width: 8px;
      height: 8px;
      border-radius: 999px;
      background: var(--muted);
    }
    .status.on { color: var(--chip-ok-fg); }
    .status.on .dot { background: var(--chip-ok-fg); animation: pulse 1.4s ease-in-out infinite; }
    @keyframes pulse { 50% { opacity: .25; } }

    .lines {
      margin: 0;
      padding: 0;
      list-style: none;
      max-height: 320px;
      overflow-y: auto;
    }
    .line {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: 10px;
      padding: 9px 16px;
      border-bottom: 1px solid var(--rule);
      font-size: 14px;
    }
    .line:last-child { border-bottom: 0; }
    .at { font-size: 12px; color: var(--muted); }
    .tag {
      font-size: 11px;
      font-weight: 700;
      letter-spacing: .04em;
      border-radius: 2px;
      padding: 2px 7px;
      background: var(--chip-neutral-bg);
      color: var(--chip-neutral-fg);
      white-space: nowrap;
    }
    .text { min-width: 0; }

    .join .tag { background: var(--chip-info-bg); color: var(--chip-info-fg); }
    .admit .tag { background: var(--highlight); color: var(--ink); }
    .seat .tag { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    /* The one line that should be impossible, so it does not look like the rest. */
    .oversold { background: var(--chip-bad-bg); }
    .oversold .tag { background: var(--dhl-red); color: var(--white); }

    .empty { margin: 0; padding: 20px 16px; font-size: 14px; color: var(--muted); text-wrap: pretty; }
  `
})
export class TrafficLog {
  protected readonly traffic = inject(TrafficService);
  protected readonly feed = computed(() => this.traffic.feed());

  protected label(kind: string): string {
    switch (kind) {
      case 'join': return 'QUEUE';
      case 'admit': return 'ADMIT';
      case 'seat': return 'BOOK';
      case 'oversold': return 'ALERT';
      default: return 'INFO';
    }
  }
}
