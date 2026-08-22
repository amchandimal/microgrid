import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, shareReplay } from 'rxjs';
import {
  ConsumptionReport,
  EquityMap,
  GridStress,
  Rebates,
  SourceNote,
  SuburbDetail,
  SuburbLeague,
  Summary,
  Trend,
} from '../models/council.models';

/**
 * Everything under `/api/council`.
 *
 * <p>The token is attached by the interceptor, not here, so nothing in this
 * service has to know a session exists.
 *
 * <p>The six file-backed endpoints are cached for the life of the page: they
 * are last financial year's spreadsheets and cannot change while the dashboard
 * is open, and the dashboard reads several of them from more than one panel.
 * `gridStress()` is the exception - it is the live one, so it is refetched
 * every time it is asked for.
 */
@Injectable({ providedIn: 'root' })
export class CouncilApi {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/council';

  private cache<T>(path: string): Observable<T> {
    return this.http
      .get<T>(`${this.base}${path}`)
      .pipe(shareReplay({ bufferSize: 1, refCount: false }));
  }

  private summary$?: Observable<Summary>;
  private suburbs$?: Observable<SuburbLeague>;
  private trend$?: Observable<Trend>;
  private consumption$?: Observable<ConsumptionReport>;
  private rebates$?: Observable<Rebates>;
  private equityMap$?: Observable<EquityMap>;
  private sources$?: Observable<SourceNote[]>;

  summary(): Observable<Summary> {
    return (this.summary$ ??= this.cache<Summary>('/summary'));
  }

  suburbs(): Observable<SuburbLeague> {
    return (this.suburbs$ ??= this.cache<SuburbLeague>('/suburbs'));
  }

  trend(): Observable<Trend> {
    return (this.trend$ ??= this.cache<Trend>('/trend'));
  }

  consumption(): Observable<ConsumptionReport> {
    return (this.consumption$ ??= this.cache<ConsumptionReport>('/consumption'));
  }

  rebates(): Observable<Rebates> {
    return (this.rebates$ ??= this.cache<Rebates>('/rebates'));
  }

  equityMap(): Observable<EquityMap> {
    return (this.equityMap$ ??= this.cache<EquityMap>('/equity-map'));
  }

  sources(): Observable<SourceNote[]> {
    return (this.sources$ ??= this.cache<SourceNote[]>('/sources'));
  }

  /** Live - never cached. */
  gridStress(): Observable<GridStress> {
    return this.http.get<GridStress>(`${this.base}/grid-stress`);
  }

  suburb(locality: string): Observable<SuburbDetail> {
    return this.http.get<SuburbDetail>(
      `${this.base}/suburbs/${encodeURIComponent(locality)}`,
    );
  }
}
