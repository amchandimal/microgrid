import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { catchError, interval, of, startWith, switchMap } from 'rxjs';
import { Auth } from '../../services/auth';
import { CouncilApi } from '../../services/council-api';
import {
  ConsumptionReport,
  EquityMap,
  GridStress,
  Rebates,
  Suburb,
  SuburbDetail,
  SuburbLeague,
  Summary,
  Trend,
} from '../../models/council.models';

type SortKey =
  | 'rank'
  | 'locality'
  | 'resInstallsAlltime'
  | 'resKwAlltime'
  | 'resYoyPct'
  | 'pvDensityPct'
  | 'domesticMwhPerDwelling'
  | 'equityScore';

/** How often the live grid panel refreshes. */
const GRID_POLL_MS = 60_000;

/**
 * The Wollongong energy equity dashboard.
 *
 * <p>Seven panels answering one question: where does Council spend the next
 * Solar Banks round. The KPI strip and the charts are last financial year's
 * spreadsheets; the grid panel is the network as it stands this minute; the
 * league table and the map are the equity index computed across both.
 *
 * <p>Every panel that reports a derived number also carries where it came
 * from - the formula is shown rather than described, and the source workbook
 * is named under each chart. A ranking that decides which suburbs get money
 * has to be arguable with, and it cannot be argued with if the reader has to
 * take the arithmetic on trust.
 */
@Component({
  selector: 'app-council-dashboard',
  standalone: false,
  templateUrl: './council-dashboard.html',
  styleUrl: './council-dashboard.scss',
})
export class CouncilDashboard {
  private readonly api = inject(CouncilApi);
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly displayName = this.auth.displayName;

  // --- the file-backed panels ------------------------------------------------

  protected readonly summary = toSignal(
    this.api.summary().pipe(catchError(() => of<Summary | null>(null))),
    { initialValue: null },
  );

  protected readonly league = toSignal(
    this.api.suburbs().pipe(catchError(() => of<SuburbLeague | null>(null))),
    { initialValue: null },
  );

  protected readonly trend = toSignal(
    this.api.trend().pipe(catchError(() => of<Trend | null>(null))),
    { initialValue: null },
  );

  protected readonly consumption = toSignal(
    this.api.consumption().pipe(catchError(() => of<ConsumptionReport | null>(null))),
    { initialValue: null },
  );

  protected readonly rebates = toSignal(
    this.api.rebates().pipe(catchError(() => of<Rebates | null>(null))),
    { initialValue: null },
  );

  protected readonly equityMap = toSignal(
    this.api.equityMap().pipe(catchError(() => of<EquityMap | null>(null))),
    { initialValue: null },
  );

  // --- the live panel --------------------------------------------------------

  /** Polled, because it is the only thing here that changes while you watch. */
  protected readonly gridStress = toSignal(
    interval(GRID_POLL_MS).pipe(
      startWith(0),
      switchMap(() => this.api.gridStress().pipe(catchError(() => of<GridStress | null>(null)))),
    ),
    { initialValue: null },
  );

  // --- league table ----------------------------------------------------------

  protected readonly search = signal('');
  protected readonly hotspotsOnly = signal(false);
  protected readonly sortKey = signal<SortKey>('rank');
  protected readonly sortAsc = signal(true);

  protected readonly suburbs = computed(() => this.league()?.suburbs ?? []);

  protected readonly hotspots = computed(() =>
    this.suburbs().filter((suburb) => suburb.hotspot),
  );

  /** The denominator a rank is out of - unranked localities are not in it. */
  protected readonly rankedCount = computed(
    () => this.suburbs().filter((suburb) => suburb.ranked).length,
  );

  protected readonly visibleSuburbs = computed(() => {
    const needle = this.search().trim().toLowerCase();
    const key = this.sortKey();
    const direction = this.sortAsc() ? 1 : -1;

    return this.suburbs()
      .filter((suburb) => !this.hotspotsOnly() || suburb.hotspot)
      .filter(
        (suburb) =>
          !needle ||
          suburb.locality.toLowerCase().includes(needle) ||
          (suburb.postcode ?? '').includes(needle),
      )
      .slice()
      .sort((a, b) => direction * this.compare(a, b, key));
  });

  private compare(a: Suburb, b: Suburb, key: SortKey): number {
    if (key === 'locality') return a.locality.localeCompare(b.locality);
    const left = a[key];
    const right = b[key];
    // Unranked localities sink to the bottom whichever way the column sorts.
    if (left === null && right === null) return a.locality.localeCompare(b.locality);
    if (left === null) return 1;
    if (right === null) return -1;
    return Number(left) - Number(right);
  }

  protected sortBy(key: SortKey): void {
    if (this.sortKey() === key) {
      this.sortAsc.update((ascending) => !ascending);
      return;
    }
    this.sortKey.set(key);
    // Rank reads best low-to-high; every other column reads best high-to-low.
    this.sortAsc.set(key === 'rank' || key === 'locality');
  }

  protected sortIndicator(key: SortKey): string {
    if (this.sortKey() !== key) return '';
    return this.sortAsc() ? '▲' : '▼';
  }

  // --- detail drawer ---------------------------------------------------------

  protected readonly detail = signal<SuburbDetail | null>(null);
  protected readonly detailLoading = signal(false);

  protected openSuburb(locality: string): void {
    this.detailLoading.set(true);
    this.detail.set(null);
    this.api
      .suburb(locality)
      .pipe(
        catchError(() => of<SuburbDetail | null>(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((detail) => {
        this.detailLoading.set(false);
        this.detail.set(detail);
      });
  }

  protected closeDetail(): void {
    this.detail.set(null);
    this.detailLoading.set(false);
  }

  /** The grid reading for whichever suburb is open, if there is one. */
  protected readonly detailGrid = computed(() => {
    const name = this.detail()?.suburb.locality;
    if (!name) return null;
    return this.gridStress()?.areas.find((area) => area.name === name) ?? null;
  });

  // --- the sentence a councillor repeats -------------------------------------

  /**
   * The dashboard's one-line argument, assembled from the ranking rather than
   * written into the template - if the data changes, so does the sentence. It
   * names the two ends of the density spread and how much of the bottom
   * quartile sits in one postcode, because that concentration is the finding.
   */
  protected readonly talkingPoint = computed(() => {
    const ranked = this.suburbs().filter((suburb) => suburb.ranked);
    const hotspots = this.hotspots();
    if (!ranked.length || !hotspots.length) return null;

    const best = ranked[0];
    const worst = hotspots[hotspots.length - 1];
    if (best.pvDensityPct === null || worst.pvDensityPct === null) return null;

    // Which postcode the intervention list keeps coming back to.
    const counts = new Map<string, number>();
    for (const hotspot of hotspots) {
      const key = hotspot.postcode ?? '—';
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    const [worstPostcode, share] = [...counts.entries()].sort((a, b) => b[1] - a[1])[0];

    return (
      `Postcode ${best.postcode} runs at ${best.pvDensityPct.toFixed(1)} rooftop systems per 100 homes. ` +
      `Postcode ${worst.postcode} manages ${worst.pvDensityPct.toFixed(1)}. ` +
      `${share} of the ${hotspots.length} suburbs in the bottom quartile are in postcode ${worstPostcode} alone — ` +
      `not households that do not want solar, but households with no roof of their own to put it on. ` +
      `That is where the next Solar Banks round and the next community battery go.`
    );
  });

  // --- housekeeping ----------------------------------------------------------

  protected signOut(): void {
    this.auth.logout();
    this.router.navigateByUrl('/');
  }

  protected readonly loadFailed = computed(
    () => this.summary() === null && this.league() === null,
  );

  protected stateLabel(state: string): string {
    return { SURPLUS: 'Surplus', BALANCED: 'Balanced', PEAK: 'Peak demand' }[state] ?? state;
  }

  protected pct(value: number | null): string {
    return value === null ? '—' : `${value}%`;
  }

  protected asOf(timestamp: string | undefined): string {
    if (!timestamp) return '';
    return new Date(timestamp).toLocaleTimeString('en-AU', {
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}
