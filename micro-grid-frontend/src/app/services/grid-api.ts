import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, shareReplay } from 'rxjs';
import { GridCells, GridRegion, GridSite } from '../models/grid.models';
import { apiUrl } from '../core/api';

/** A viewport, in plain numbers - this service stays clear of Leaflet. */
export interface ViewportBounds {
  south: number;
  west: number;
  north: number;
  east: number;
}

/** Everything the map draws comes from GridController. */
@Injectable({ providedIn: 'root' })
export class GridApi {
  private readonly http = inject(HttpClient);
  private readonly base = apiUrl('/api/grid');

  /**
   * Extent and resolution limits. Cached - it never changes for a running
   * server, and the map asks for it on every reload.
   */
  readonly region$: Observable<GridRegion> = this.http
    .get<GridRegion>(`${this.base}/region`)
    .pipe(shareReplay({ bufferSize: 1, refCount: false }));

  readonly sites$: Observable<GridSite[]> = this.http
    .get<GridSite[]>(`${this.base}/sites`)
    .pipe(shareReplay({ bufferSize: 1, refCount: false }));

  /** The overlay for one viewport, at the resolution `zoom` implies. */
  cells(bounds: ViewportBounds, zoom: number): Observable<GridCells> {
    const params = new HttpParams({ fromObject: { ...bounds, zoom } });
    return this.http.get<GridCells>(`${this.base}/cells`, { params });
  }
}
