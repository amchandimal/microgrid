import { Component, computed, inject, signal } from '@angular/core';
import { GridData } from '../../services/grid-data';
import { GridArea } from '../../models/grid.models';

/** Which column the area list is ordered on. */
type AreaSort = 'households' | 'commercial';

/**
 * The public grid dashboard.
 *
 * <p>The headline figures are Wollongong's real rooftop fleet - 27,773 systems
 * and 189,737 kW, summed from the two Total System exports the Council
 * publishes - and the area list is that fleet apportioned across the map by
 * each area's share of modelled supply and demand. All of it arrives from the
 * API already computed, out of SQLite; nothing here does arithmetic on a
 * spreadsheet.
 *
 * <p>The prices below it are still the local placeholder curve, and are
 * labelled as such rather than being left to look like the rest.
 */
@Component({
  selector: 'app-dashboard-panel',
  standalone: false,
  templateUrl: './dashboard-panel.html',
  styleUrl: './dashboard-panel.scss',
})
export class DashboardPanel {
  private readonly gridData = inject(GridData);

  protected readonly sites = this.gridData.sites;
  protected readonly installations = this.gridData.installations;
  protected readonly priceCurve = this.gridData.getPriceCurve();

  // --- the real fleet --------------------------------------------------------

  protected readonly totals = computed(() => this.installations()?.totals ?? null);

  /** "2001-01 to 2025-12" as a person would say it. */
  protected readonly period = computed(() => {
    const raw = this.installations()?.period;
    if (!raw) return null;
    return raw.replace(/(\d{4})-(\d{2})/g, (_, year, month) => `${MONTHS[+month - 1]} ${year}`);
  });

  protected readonly areaSort = signal<AreaSort>('households');

  protected readonly areas = computed<GridArea[]>(() => {
    const areas = this.installations()?.areas ?? [];
    const key = this.areaSort() === 'households' ? 'residentialInstalls' : 'commercialInstalls';
    return areas
      .slice()
      .filter((area) => area.residentialInstalls > 0 || area.commercialInstalls > 0)
      .sort((a, b) => b[key] - a[key]);
  });

  /** The busiest area, so every bar is drawn against the same top. */
  private readonly widestArea = computed(() =>
    Math.max(1, ...this.areas().map((area) => this.areaValue(area))),
  );

  protected areaValue(area: GridArea): number {
    return this.areaSort() === 'households'
      ? area.residentialInstalls
      : area.commercialInstalls;
  }

  protected areaBar(area: GridArea): number {
    return (this.areaValue(area) / this.widestArea()) * 100;
  }

  protected sharePct(area: GridArea): number {
    return this.areaSort() === 'households' ? area.supplySharePct : area.demandSharePct;
  }

  protected setAreaSort(sort: AreaSort): void {
    this.areaSort.set(sort);
  }

  // --- the sites the map pins ------------------------------------------------

  protected readonly suppliers = computed(() =>
    this.sites().filter((s) => s.type === 'SUPPLIER'),
  );
  protected readonly households = computed(() =>
    this.sites().filter((s) => s.type === 'HOUSEHOLD'),
  );

  protected readonly supplierCount = computed(() => this.suppliers().length);
  protected readonly householdCount = computed(() => this.households().length);

  protected readonly onlineSuppliers = computed(
    () => this.suppliers().filter((s) => s.status === 'ONLINE').length,
  );
  protected readonly onlineHouseholds = computed(
    () => this.households().filter((s) => s.status === 'ONLINE').length,
  );

  // --- the indicative trading strip ------------------------------------------

  protected readonly buyPrice = signal(this.gridData.getBuyPrice());
  protected readonly sellPrice = signal(this.gridData.getSellPrice());

  protected readonly spread = computed(() =>
    +(this.buyPrice() - this.sellPrice()).toFixed(2),
  );
  protected readonly margin = computed(() =>
    Math.round((this.spread() / this.buyPrice()) * 100),
  );

  private readonly maxPrice = Math.max(
    ...this.priceCurve.map((p) => Math.max(p.buy, p.sell)),
  );

  protected barHeight(value: number): string {
    return `${Math.round((value / this.maxPrice) * 100)}%`;
  }

  protected statusClass(status: string): string {
    return `status status--${status.toLowerCase()}`;
  }
}

const MONTHS = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];
