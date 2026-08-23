import {
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
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
type TextSize = 'md' | 'lg' | 'xl';
type Contrast = 'normal' | 'high';

/**
 * One answer in a choice group.
 *
 * <p>`tone` names a colour slot rather than a colour: the stylesheet owns the
 * hex, and every tone in it clears 4.5:1 on the card. `glyph` and `sub` are
 * there so a tile never says something with hue alone - the icon, the label,
 * the supporting line and the tick all carry the same meaning.
 */
interface Choice<T> {
  value: T;
  label: string;
  glyph: string;
  tone: string;
  sub?: string;
}

/** A question the plan cannot be built without. */
interface RequiredQuestion {
  id: string;
  label: string;
  done: boolean;
}

/**
 * The Energy Outcome Wizard.
 *
 * <p>Three questions deep at most, then a ranked plan. The point is that a
 * renter in a flat with no roof comes out of it with real numbers, so the
 * no-roof branches are first-class rather than a fallback: "not sure" is
 * always answerable, nothing asks about income, and every figure is framed as
 * something gained.
 *
 * <p>The same argument runs through the interface. This is the page a pensioner
 * is meant to get through on a phone in bright sun, so every answer is a large
 * tile with its own icon and colour, every step change is announced and moves
 * focus, nothing required is enforced by a dead disabled button, and the reader
 * can scale the text or raise the contrast without leaving the page.
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
  private readonly injector = inject(Injector);
  private readonly address$ = new Subject<string>();

  /** The heading of whichever step is on screen - focus lands here on a move. */
  private readonly stepAnchor = viewChild<ElementRef<HTMLElement>>('stepAnchor');
  /** The "still to answer" summary, focused when someone submits too early. */
  private readonly todoBox = viewChild<ElementRef<HTMLElement>>('todoBox');

  protected readonly step = signal<Step>('location');
  protected readonly addressQuery = signal('');
  protected readonly addressResults = signal<GeoResult[]>([]);
  protected readonly searching = signal(false);
  protected readonly locating = signal(false);
  protected readonly noMatches = signal(false);
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
  /** Set once someone has pressed "Show my plan", so nothing nags before then. */
  protected readonly attempted = signal(false);

  /** Read by the one polite live region, so announcements never collide. */
  protected readonly announcement = signal('');

  // --- reader preferences ----------------------------------------------------

  private static readonly PREFS_KEY = 'gs-display-prefs';
  protected readonly textSize = signal<TextSize>('md');
  protected readonly contrast = signal<Contrast>('normal');

  protected readonly textSizes: ReadonlyArray<{ value: TextSize; label: string }> = [
    { value: 'md', label: 'Standard' },
    { value: 'lg', label: 'Large' },
    { value: 'xl', label: 'Largest' },
  ];

  // --- answer sets -----------------------------------------------------------

  /** Yes / No / Not sure, reused by the meter, solar and battery questions. */
  protected readonly answerChoices: ReadonlyArray<Choice<Answer>> = [
    { value: 'YES', label: 'Yes', glyph: '✔️', tone: 'green' },
    { value: 'NO', label: 'No', glyph: '✖️', tone: 'slate' },
    {
      value: 'NOT_SURE',
      label: 'Not sure',
      glyph: '❔',
      tone: 'amber',
      sub: 'That is fine — we still build your plan',
    },
  ];

  protected readonly presenceChoices: ReadonlyArray<Choice<Presence>> = [
    { value: 'OFTEN', label: 'Often', glyph: '☀️', tone: 'amber', sub: 'Most weekdays' },
    { value: 'SOMETIMES', label: 'Sometimes', glyph: '⛅', tone: 'teal', sub: 'A day or two' },
    { value: 'RARELY', label: 'Rarely', glyph: '🌙', tone: 'indigo', sub: 'Out most days — timers still work' },
  ];

  protected readonly upfrontChoices: ReadonlyArray<Choice<Upfront>> = [
    { value: 'NO_UPFRONT', label: 'No upfront cost', glyph: '💸', tone: 'green', sub: 'Nothing to pay to start' },
    {
      value: 'OPEN_TO_INVESTMENT',
      label: 'Open to a one-off',
      glyph: '📈',
      tone: 'blue',
      sub: 'A single cost that pays itself back',
    },
  ];

  /** Landlords are asked about a property; everyone else about a home. */
  protected readonly homeChoices = computed<ReadonlyArray<Choice<HomeType>>>(() =>
    this.role() === 'LANDLORD'
      ? [
          { value: 'SINGLE_DWELLING', label: 'Single dwelling', glyph: '🏠', tone: 'teal', sub: 'One home on one title' },
          {
            value: 'MULTI_UNIT',
            label: 'Multi-unit or block',
            glyph: '🏢',
            tone: 'indigo',
            sub: 'Two or more units — unlocks the 50% rebate',
          },
        ]
      : [
          { value: 'HOUSE', label: 'House', glyph: '🏡', tone: 'teal', sub: 'Standalone, with its own roof' },
          {
            value: 'APARTMENT',
            label: 'Apartment or unit',
            glyph: '🏢',
            tone: 'indigo',
            sub: 'No roof needed — you still have options',
          },
          { value: 'TOWNHOUSE', label: 'Townhouse', glyph: '🏘️', tone: 'violet', sub: 'Attached, own front door' },
        ],
  );

  protected readonly roleChoices: ReadonlyArray<Choice<Role>> = [
    {
      value: 'HOMEOWNER',
      label: 'I own the home I live in',
      glyph: '🏠',
      tone: 'green',
      sub: 'Solar, battery and selling to neighbours',
    },
    {
      value: 'LANDLORD',
      label: 'I own a property others live in',
      glyph: '🔑',
      tone: 'amber',
      sub: 'Rebates on installs, and solar as a tenancy feature',
    },
    {
      value: 'RENTER',
      label: 'I rent my home',
      glyph: '🛋️',
      tone: 'indigo',
      sub: 'No roof needed — most options cost $0 upfront',
    },
  ];

  // --- derived state ---------------------------------------------------------

  private static readonly ORDER: readonly Step[] = ['location', 'role', 'questions', 'plan'];
  private static readonly LABELS: Record<Step, string> = {
    location: 'Where you live',
    role: 'About you',
    questions: 'Your place',
    plan: 'Your plan',
  };

  protected readonly stepCount = GridSmart.ORDER.length;

  protected readonly progressSteps = computed(() => {
    const here = this.step();
    const done: Record<Step, boolean> = {
      location: !!this.located(),
      role: !!this.role(),
      questions: !!this.plan(),
      plan: false,
    };
    return GridSmart.ORDER.map((key, i) => ({
      key,
      n: i + 1,
      label: GridSmart.LABELS[key],
      done: done[key],
      current: key === here,
    }));
  });

  protected readonly stepIndex = computed(() => GridSmart.ORDER.indexOf(this.step()));
  protected readonly stepTitle = computed(() => GridSmart.LABELS[this.step()]);

  /**
   * The questions the plan genuinely cannot be built without.
   *
   * <p>Everything else stays optional on purpose - a plan built from two
   * answers is still a plan, and demanding more than that is how people fall
   * out of a form.
   */
  protected readonly requiredQuestions = computed<RequiredQuestion[]>(() => {
    const role = this.role();
    if (!role) return [];
    const questions: RequiredQuestion[] = [
      {
        id: 'q-home-type',
        label: role === 'LANDLORD' ? 'What type of property is it?' : 'What type of home is it?',
        done: !!this.homeType(),
      },
    ];
    if (role === 'RENTER') {
      questions.push({
        id: 'q-smart-meter',
        label: 'Do you have a smart meter?',
        done: !!this.smartMeter(),
      });
    } else {
      questions.push({
        id: 'q-solar',
        label: role === 'LANDLORD' ? 'Is solar installed on the property?' : 'Do you have rooftop solar?',
        done: !!this.hasSolar(),
      });
    }
    return questions;
  });

  protected readonly missing = computed(() => this.requiredQuestions().filter((q) => !q.done));
  protected readonly answeredCount = computed(
    () => this.requiredQuestions().length - this.missing().length,
  );

  /** The single biggest win - what the plan leads with. */
  protected readonly headline = computed<Recommendation | null>(() => {
    const p = this.plan();
    if (!p) return null;
    return [...p.doThisWeek, ...p.doThisMonth][0] ?? null;
  });

  constructor() {
    this.restorePrefs();

    this.address$
      .pipe(
        debounceTime(400),
        distinctUntilChanged(),
        switchMap((q) => {
          if (q.trim().length < 3) {
            this.searching.set(false);
            this.noMatches.set(false);
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
        const asked = this.addressQuery().trim().length >= 3;
        this.noMatches.set(asked && results.length === 0);
        if (results.length) {
          this.announcement.set(
            `${results.length} ${results.length === 1 ? 'match' : 'matches'} found. Choose one from the list below.`,
          );
        } else if (asked) {
          this.announcement.set('No matches found.');
        }
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

  // --- reader preferences ----------------------------------------------------

  protected setTextSize(size: TextSize): void {
    this.textSize.set(size);
    this.announcement.set(
      `Text size: ${GridSmart.textSizeName(size)}.`,
    );
    this.storePrefs();
  }

  protected toggleContrast(): void {
    const high = this.contrast() === 'normal';
    this.contrast.set(high ? 'high' : 'normal');
    this.announcement.set(high ? 'Higher contrast turned on.' : 'Higher contrast turned off.');
    this.storePrefs();
  }

  private static textSizeName(size: TextSize): string {
    return { md: 'standard', lg: 'large', xl: 'largest' }[size];
  }

  private restorePrefs(): void {
    try {
      const raw = localStorage.getItem(GridSmart.PREFS_KEY);
      if (!raw) return;
      const saved = JSON.parse(raw) as Partial<{ textSize: TextSize; contrast: Contrast }>;
      if (saved.textSize === 'md' || saved.textSize === 'lg' || saved.textSize === 'xl') {
        this.textSize.set(saved.textSize);
      }
      if (saved.contrast === 'normal' || saved.contrast === 'high') {
        this.contrast.set(saved.contrast);
      }
    } catch {
      // A locked-down or full localStorage is no reason to fail the page.
    }
  }

  private storePrefs(): void {
    try {
      localStorage.setItem(
        GridSmart.PREFS_KEY,
        JSON.stringify({ textSize: this.textSize(), contrast: this.contrast() }),
      );
    } catch {
      // As above - the preference simply does not outlive the session.
    }
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
    this.noMatches.set(false);
    this.addressQuery.set(result.displayName);
    this.error.set(null);
    this.goTo('role');
  }

  protected clearLocation(): void {
    this.located.set(null);
    this.addressQuery.set('');
    this.addressResults.set([]);
    this.noMatches.set(false);
  }

  /** Coming back to a step you have already answered should not re-answer it. */
  protected continueFromLocation(): void {
    if (this.located()) this.goTo('role');
  }

  /**
   * The typing-free way in.
   *
   * <p>Spelling a suburb into a phone is the step this wizard loses people on,
   * so the browser is allowed to answer it instead. It is opt-in by press, and
   * a refusal or a failure just puts the text field back in front of them.
   */
  protected useMyLocation(): void {
    if (!navigator.geolocation) {
      this.error.set('This browser cannot share a location. Type a suburb or postcode instead.');
      return;
    }
    this.locating.set(true);
    this.error.set(null);
    this.announcement.set('Finding your location.');
    navigator.geolocation.getCurrentPosition(
      (position) => {
        this.geocoding
          .reverse(position.coords.latitude, position.coords.longitude)
          .pipe(
            catchError(() => of(null)),
            takeUntilDestroyed(this.destroyRef),
          )
          .subscribe((result) => {
            this.locating.set(false);
            if (result) {
              this.chooseAddress(result);
            } else {
              this.error.set('We could not name that spot. Type a suburb or postcode instead.');
            }
          });
      },
      () => {
        this.locating.set(false);
        this.error.set('We could not get your location. You can type a suburb or postcode instead.');
      },
      { enableHighAccuracy: false, timeout: 10000, maximumAge: 300000 },
    );
  }

  // --- step 1: who are you --------------------------------------------------

  protected chooseRole(role: Role): void {
    this.role.set(role);
    // Landlords answer about the property, not a home they live in.
    this.homeType.set(role === 'LANDLORD' ? null : this.homeType());
    this.attempted.set(false);
    this.goTo('questions');
  }

  /** The fourth tile: Council staff go to the equity dashboard, not a plan. */
  protected goToCouncil(): void {
    this.router.navigateByUrl('/login/council');
  }

  // --- step 2 -> plan -------------------------------------------------------

  protected get canBuild(): boolean {
    return !!this.located() && !!this.role() && this.missing().length === 0;
  }

  /**
   * Sends someone to the answer they have not given yet.
   *
   * <p>The submit button stays live rather than going grey: a disabled control
   * is silent about why it is disabled, and several screen readers skip past it
   * entirely. Pressing it with something outstanding names what is outstanding.
   */
  protected focusQuestion(id: string): void {
    const target = document.getElementById(id);
    if (!target) return;
    const still = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    target.scrollIntoView({ behavior: still ? 'auto' : 'smooth', block: 'center' });
    target.focus({ preventScroll: true });
  }

  protected buildPlan(): void {
    this.attempted.set(true);

    const outstanding = this.missing();
    if (outstanding.length) {
      this.announcement.set(
        `${outstanding.length} ${outstanding.length === 1 ? 'question' : 'questions'} still need an answer.`,
      );
      afterNextRender(() => this.todoBox()?.nativeElement.focus(), { injector: this.injector });
      return;
    }

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
    this.announcement.set('Building your plan.');
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
          this.goTo('plan');
        }
      });
  }

  /** Clears the answers too - otherwise the last run leaks into the next. */
  protected startOver(): void {
    this.plan.set(null);
    this.error.set(null);
    this.attempted.set(false);
    this.addressQuery.set('');
    this.addressResults.set([]);
    this.noMatches.set(false);
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
    this.goTo('location');
  }

  protected back(): void {
    const i = this.stepIndex();
    if (i > 0) this.goTo(GridSmart.ORDER[i - 1]);
  }

  /**
   * Moves a step, and moves the reader with it.
   *
   * <p>Swapping the middle of the page and leaving focus on the button that did
   * it strands anyone who is not watching the screen. Focus goes to the new
   * heading, and the live region says where they have landed.
   */
  private goTo(step: Step): void {
    this.step.set(step);
    const n = GridSmart.ORDER.indexOf(step) + 1;
    this.announcement.set(`Step ${n} of ${this.stepCount}: ${GridSmart.LABELS[step]}.`);
    afterNextRender(() => this.stepAnchor()?.nativeElement.focus(), { injector: this.injector });
  }

  /** Paper is an accessibility feature: a plan you can hold is a plan you keep. */
  protected printPlan(): void {
    window.print();
  }

  // --- display helpers ------------------------------------------------------

  protected money(low: number, high: number): string {
    return low === high ? `$${low}` : `$${low}–${high}`;
  }

  protected stateLabel(state: string): string {
    return { SURPLUS: 'Surplus', BALANCED: 'Balanced', PEAK: 'Peak demand' }[state] ?? state;
  }

  protected stateGlyph(state: string): string {
    return { SURPLUS: '🔆', BALANCED: '⚖️', PEAK: '🔺' }[state] ?? '⚡';
  }

  /** The stripe and the badge take their hue from here, the word from above. */
  protected stateTone(state: string): string {
    return { SURPLUS: 'green', BALANCED: 'amber', PEAK: 'rose' }[state] ?? 'slate';
  }

  protected stateNote(state: string): string {
    return (
      {
        SURPLUS: 'More local power than the area is using — the cheap window',
        BALANCED: 'Supply and demand are level — normal prices',
        PEAK: 'The area is drawing more than it makes — prices are highest',
      }[state] ?? ''
    );
  }

  protected effortLabel(effort: string): string {
    return { ONE_TAP: 'One tap', SHORT: 'A few minutes', INVOLVED: 'A bigger job' }[effort] ?? effort;
  }

  protected effortGlyph(effort: string): string {
    return { ONE_TAP: '👆', SHORT: '⏱️', INVOLVED: '🛠️' }[effort] ?? '•';
  }
}
