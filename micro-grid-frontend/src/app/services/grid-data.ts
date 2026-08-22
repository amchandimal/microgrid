import { Injectable, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { GridApi } from './grid-api';
import { GridSite, PricePoint } from '../models/grid.models';

/**
 * Shared dashboard state.
 *
 * The network itself - the region, the sites and the overlay - comes from
 * GridController. What is still local is the trading data, which has no
 * backend yet.
 */
@Injectable({ providedIn: 'root' })
export class GridData {
  private readonly api = inject(GridApi);

  /** Sites from the server. Empty until the first response lands. */
  readonly sites = toSignal(
    this.api.sites$.pipe(catchError(() => of<GridSite[]>([]))),
    { initialValue: [] as GridSite[] },
  );

  /** Buying / selling price in cents per kWh across the trading day. */
  getPriceCurve(): PricePoint[] {
    return [
      { hour: '00', buy: 21.4, sell: 8.1 },
      { hour: '03', buy: 19.8, sell: 7.4 },
      { hour: '06', buy: 24.6, sell: 9.2 },
      { hour: '09', buy: 27.9, sell: 11.6 },
      { hour: '12', buy: 22.3, sell: 14.8 },
      { hour: '15', buy: 25.1, sell: 13.2 },
      { hour: '18', buy: 38.7, sell: 19.4 },
      { hour: '21', buy: 29.5, sell: 12.7 },
    ];
  }

  getBuyPrice(): number {
    return 32.45;
  }

  getSellPrice(): number {
    return 14.8;
  }
}
