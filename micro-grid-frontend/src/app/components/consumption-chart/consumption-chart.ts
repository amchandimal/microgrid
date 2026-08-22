import { Component, computed, input, signal } from '@angular/core';
import { Consumption } from '../../models/council.models';
import { compact } from '../../services/chart-scale';

type View = 'all' | 'households';

/** One bar, already split into the three sectors. */
interface Row {
  postcode: string;
  localities: string[];
  households: number;
  commercial: number;
  industrial: number;
  total: number;
  perDwelling: number | null;
  accounts: number;
  /** Widths as a percentage of the widest bar in the current view. */
  widths: { households: number; commercial: number; industrial: number };
  barPct: number;
}

/**
 * Where Wollongong's electricity actually goes, by postcode.
 *
 * <p>Horizontal because the categories are postcodes with a list of suburbs
 * attached, and those labels need room. Stacked because the question is
 * part-to-whole - how much of a postcode's load is households.
 *
 * <p>The chart has two views for a reason that is itself the finding: one
 * postcode draws 880 GWh of industrial load, which is more than every
 * household in the LGA put together, so on a shared axis the domestic bars are
 * a few pixels wide. "Households only" is not a cosmetic zoom - it is the only
 * view in which the residential numbers can be compared at all, and having to
 * switch to it makes the point better than a caption would.
 */
@Component({
  selector: 'app-consumption-chart',
  standalone: false,
  templateUrl: './consumption-chart.html',
  styleUrl: './consumption-chart.scss',
})
export class ConsumptionChart {
  readonly postcodes = input.required<Consumption[]>();

  protected readonly view = signal<View>('all');
  protected readonly hovered = signal<{ row: Row; x: number; y: number } | null>(null);

  protected readonly rows = computed<Row[]>(() => {
    const view = this.view();
    const base = this.postcodes().map((row) => ({
      postcode: row.postcode,
      localities: row.localities,
      // The source reports controlled load beside domestic; both are household
      // electricity - hot water on a night tariff is still the household's bill.
      households: row.domesticMwh + row.controlledLoadMwh,
      commercial: row.commercialMwh,
      industrial: row.industrialMwh,
      total: row.totalMwh,
      perDwelling: row.domesticMwhPerDwelling,
      accounts: row.domesticAccounts,
    }));

    const measure = (row: (typeof base)[number]) =>
      view === 'households' ? row.households : row.total;
    const widest = Math.max(1, ...base.map(measure));

    return base
      .slice()
      .sort((a, b) => measure(b) - measure(a))
      .map((row) => ({
        ...row,
        barPct: (measure(row) / widest) * 100,
        widths: {
          households: (row.households / Math.max(measure(row), 1)) * 100,
          commercial:
            view === 'households' ? 0 : (row.commercial / Math.max(row.total, 1)) * 100,
          industrial:
            view === 'households' ? 0 : (row.industrial / Math.max(row.total, 1)) * 100,
        },
      }));
  });

  protected setView(view: View): void {
    this.view.set(view);
  }

  protected onEnter(row: Row, event: MouseEvent): void {
    const box = (event.currentTarget as HTMLElement)
      .closest('.consumption')!
      .getBoundingClientRect();
    this.hovered.set({
      row,
      x: event.clientX - box.left,
      y: event.clientY - box.top,
    });
  }

  protected onLeave(): void {
    this.hovered.set(null);
  }

  /** Keeps the tooltip inside the card near the right-hand edge. */
  protected tipStyle(x: number, y: number): Record<string, string> {
    return {
      left: `${Math.max(8, Math.min(x + 14, 9999))}px`,
      top: `${y + 14}px`,
    };
  }

  protected gwh(mwh: number): string {
    return compact(Math.round(mwh / 1000)) + ' GWh';
  }

  protected mwh(value: number): string {
    return Math.round(value).toLocaleString('en-AU');
  }

  /** The value that rides the end of the bar - one label, not four. */
  protected endLabel(row: Row): string {
    return this.gwh(this.view() === 'households' ? row.households : row.total);
  }

  protected suburbList(row: Row): string {
    if (!row.localities.length) return 'no Wollongong localities mapped';
    return row.localities.slice(0, 4).join(', ')
      + (row.localities.length > 4 ? ` +${row.localities.length - 4} more` : '');
  }
}
