import { Component, input } from '@angular/core';

/**
 * A section the design calls for whose data source does not exist yet.
 *
 * It says so, rather than showing sample rows. Fabricated deploy history on a
 * page whose whole argument is "these numbers come from the service that owns
 * them" would undo the argument, and a viewer who sees an honest gap trusts the
 * numbers beside it more than one who later discovers a filled table was fake.
 */
@Component({
  selector: 'rb-placeholder',
  template: `
    <div class="card">
      <div class="reason" style="margin-top: 0;">{{ note() }}</div>
    </div>
  `
})
export class Placeholder {
  readonly note = input.required<string>();
}
