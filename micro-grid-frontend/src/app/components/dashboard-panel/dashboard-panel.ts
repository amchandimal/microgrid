import { Component, computed, inject, signal } from '@angular/core';
import { GridData } from '../../services/grid-data';
import { GridSite } from '../../models/grid.models';

@Component({
  selector: 'app-dashboard-panel',
  standalone: false,
  templateUrl: './dashboard-panel.html',
  styleUrl: './dashboard-panel.scss',
})
export class DashboardPanel {
  private readonly gridData = inject(GridData);

  protected readonly sites = this.gridData.sites;
  protected readonly priceCurve = this.gridData.getPriceCurve();

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

  protected readonly totalCapacityKw = computed(() =>
    this.suppliers().reduce((sum, s) => sum + s.capacityKw, 0),
  );
  protected readonly totalDemandKw = computed(() =>
    this.households().reduce((sum, s) => sum + s.capacityKw, 0),
  );

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
