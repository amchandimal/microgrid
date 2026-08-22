import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  Subject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  interval,
  of,
  switchMap,
} from 'rxjs';
import { Router } from '@angular/router';
import { Geocoding } from '../../services/geocoding';
import { WizardApi } from '../../services/wizard-api';
import {
  Answer,
  GeoResult,
  HomeType,
  OutcomePlan,
  Presence,
  Recommendation,
  Role,
  Upfront,
  WizardAnswers,
} from '../../models/grid.models';

type Step = 'location' | 'role' | 'questions' | 'plan';

/**
 * The Energy Outcome Wizard.
 *
 * <p>Three questions deep at most, then a ranked plan. The point is that a
 * renter in a flat with no roof comes out of it with real numbers, so the
 * no-roof branches are first-class rather than a fallback: "not sure" is
 * always answerable, nothing asks about income, and every figure is framed as
 * something gained.
 */
@Component({
  selector: 'app-grid-smart',
  standalone: false,
  templateUrl: './grid-smart.html',
  styleUrl: './grid-smart.scss',
})
export class GridSmart {
  private readonly geocoding = inject(Geocoding);
  private readonly api = inject(WizardApi);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly address$ = new Subject<string>();

  protected readonly step = signal<Step>('location');
  protected readonly addressQuery = signal('');
  protected readonly addressResults = signal<GeoResult[]>([]);
  protected readonly searching = signal(false);
  protected readonly located = signal<{ label: string; suburb: string; lat: number; lng: number } | null>(null);

  protected readonly role = signal<Role | null>(null);
  protected readonly homeType = signal<HomeType | null>(null);
  protected readonly smartMeter = signal<Answer | null>(null);
  protected readonly daytimePresence = signal<Presence | null>(null);
  protected readonly concessionCard = signal(false);
  protected readonly upfront = signal<Upfront | null>(null);
  protected readonly bigAppliances = signal(false);
  protected readonly hasSolar = signal<Answer | null>(null);
  protected readonly solarKw = signal<number | null>(null);
  protected readonly hasBattery = signal<Answer | null>(null);
  protected readonly wantsToSell = signal(false);
  protected readonly openToSubsidised = signal(false);
  protected readonly offerToTenants = signal(false);

  protected readonly plan = signal<OutcomePlan | null>(null);
  protected readonly building = signal(false);
  protected readonly error = signal<string | null>(null);

  /** The single biggest win - what the plan leads with. */
  protected readonly headline = computed<Recommendation | null>(() => {
    const p = this.plan();
    if (!p) return null;
    return [...p.doThisWeek, ...p.doThisMonth][0] ?? null;
  });

  constructor() {
    this.address$
      .pipe(
        debounceTime(400),
        distinctUntilChanged(),
        switchMap((q) => {
          if (q.trim().length < 3) {
            this.searching.set(false);
            return of<GeoResult[]>([]);
          }
          this.searching.set(true);
          return this.geocoding.search(q).pipe(catchError(() => of<GeoResult[]>([])));
        }),
        takeUntilDestroyed(),
      )
      .subscribe((results) => {
        this.searching.set(false);
        this.addressResults.set(results);
      });

    // The plan page polls the grid, as the spec asks - the advice changes with
    // the state, so a stale reading is worse than none.
    interval(5 * 60 * 1000)
      .pipe(
        switchMap(() => {
          const at = this.located();
          if (!at || !this.plan()) return of(null);
          return this.api.status(at.lat, at.lng).pipe(catchError(() => of(null)));
        }),
        takeUntilDestroyed(),
      )
      .subscribe((status) => {
        const current = this.plan();
        if (status && current) this.plan.set({ ...current, grid: status });
      });
  }

  // --- step 0: where do you live -------------------------------------------

  protected onAddressInput(value: string): void {
    this.addressQuery.set(value);
    this.address$.next(value);
  }

  protected chooseAddress(result: GeoResult): void {
    this.located.set({
      label: result.displayName,
      suburb: result.displayName.split(',')[0].trim(),
      lat: result.lat,
      lng: result.lng,
    });
    this.addressResults.set([]);
    this.addressQuery.set(result.displayName);
    this.error.set(null);
    this.step.set('role');
  }

  // --- step 1: who are you --------------------------------------------------

  protected chooseRole(role: Role): void {
    this.role.set(role);
    // Landlords answer about the property, not a home they live in.
    this.homeType.set(role === 'LANDLORD' ? null : this.homeType());
    this.step.set('questions');
  }

  /** The fourth tile: Council staff go to the equity dashboard, not a plan. */
  protected goToCouncil(): void {
    this.router.navigateByUrl('/login/council');
  }

  // --- step 2 -> plan -------------------------------------------------------

  protected get canBuild(): boolean {
    const role = this.role();
    if (!role || !this.located()) return false;
    if (role === 'RENTER') return !!this.homeType() && !!this.smartMeter();
    if (role === 'HOMEOWNER') return !!this.homeType() && !!this.hasSolar();
    return !!this.homeType() && !!this.hasSolar();
  }

  protected buildPlan(): void {
    const at = this.located();
    const role = this.role();
    if (!at || !role) return;

    const answers: WizardAnswers = {
      address: at.label,
      suburb: at.suburb,
      lat: at.lat,
      lng: at.lng,
      role,
      homeType: this.homeType(),
      smartMeter: this.smartMeter(),
      daytimePresence: this.daytimePresence(),
      concessionCard: this.concessionCard(),
      upfrontPreference: this.upfront(),
      bigAppliances: this.bigAppliances(),
      hasSolar: this.hasSolar(),
      solarKw: this.solarKw(),
      hasBattery: this.hasBattery(),
      wantsToSellSurplus: this.wantsToSell(),
      openToSubsidisedSolar: this.openToSubsidised(),
      wouldOfferSolarToTenants: this.offerToTenants(),
    };

    this.building.set(true);
    this.error.set(null);
    this.api
      .plan(answers)
      .pipe(
        catchError((e) => {
          this.error.set(
            e?.status === 404
              ? 'We do not have network data for that address yet. Try a nearby Illawarra suburb.'
              : 'Could not build your plan just now. Please try again.',
          );
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((plan) => {
        this.building.set(false);
        if (plan) {
          this.plan.set(plan);
          this.step.set('plan');
        }
      });
  }

  /** Clears the answers too - otherwise the last run leaks into the next. */
  protected startOver(): void {
    this.plan.set(null);
    this.error.set(null);
    this.addressQuery.set('');
    this.addressResults.set([]);
    this.located.set(null);
    this.role.set(null);
    this.homeType.set(null);
    this.smartMeter.set(null);
    this.daytimePresence.set(null);
    this.concessionCard.set(false);
    this.upfront.set(null);
    this.bigAppliances.set(false);
    this.hasSolar.set(null);
    this.solarKw.set(null);
    this.hasBattery.set(null);
    this.wantsToSell.set(false);
    this.openToSubsidised.set(false);
    this.offerToTenants.set(false);
    this.step.set('location');
  }

  protected back(): void {
    const order: Step[] = ['location', 'role', 'questions', 'plan'];
    const i = order.indexOf(this.step());
    if (i > 0) this.step.set(order[i - 1]);
  }

  // --- display helpers ------------------------------------------------------

  protected money(low: number, high: number): string {
    return low === high ? `$${low}` : `$${low}–${high}`;
  }

  protected stateLabel(state: string): string {
    return { SURPLUS: 'Surplus', BALANCED: 'Balanced', PEAK: 'Peak demand' }[state] ?? state;
  }

  protected effortLabel(effort: string): string {
    return { ONE_TAP: 'One tap', SHORT: 'A few minutes', INVOLVED: 'A bigger job' }[effort] ?? effort;
  }
}
