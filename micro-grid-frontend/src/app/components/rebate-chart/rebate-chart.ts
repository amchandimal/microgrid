import {
  AfterViewInit,
  Component,
  ElementRef,
  NgZone,
  OnDestroy,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { RebatePoint, Rebates } from '../../models/council.models';
import { compact, niceTicks, polyline } from '../../services/chart-scale';

const LEFT = 52;
const RIGHT = 96;
const TOP = 14;
const HEIGHT = 168;
const AXIS_HEIGHT = 20;

/**
 * Rebate take-up against entitlement, FY2017-18 to FY2022-23.
 *
 * <p>Two lines and the space between them. Entitlement is estimated from
 * Centrelink, Veterans' Affairs and ATO records; take-up is counted from what
 * retailers actually paid out. Neither line is the story - the gap is, so it is
 * the only thing on the chart that is filled.
 *
 * <p>Both series are counts of customers, so they belong on one axis. There is
 * no second scale here and there should never be one.
 */
@Component({
  selector: 'app-rebate-chart',
  standalone: false,
  templateUrl: './rebate-chart.html',
  styleUrl: './rebate-chart.scss',
})
export class RebateChart implements AfterViewInit, OnDestroy {
  readonly rebates = input.required<Rebates>();

  private readonly zone = inject(NgZone);
  private resize?: ResizeObserver;

  protected readonly width = signal(560);
  protected readonly programName = signal<string | null>(null);
  protected readonly hoverIndex = signal<number | null>(null);

  protected readonly top = TOP;
  protected readonly height = HEIGHT;
  protected readonly left = LEFT;
  protected readonly totalHeight = TOP + HEIGHT + AXIS_HEIGHT;

  private readonly element = inject(ElementRef).nativeElement as HTMLElement;

  constructor() {
    if (typeof ResizeObserver !== 'undefined') {
      this.zone.runOutsideAngular(() => {
        this.resize = new ResizeObserver(([entry]) =>
          this.measured(Math.round(entry.contentRect.width)),
        );
        this.resize.observe(this.element);
      });
    }
  }

  /** See {@code TrendChart} - ResizeObserver alone is not enough. */
  ngAfterViewInit(): void {
    this.measured(Math.round(this.element.getBoundingClientRect().width));
  }

  private measured(width: number): void {
    if (width > 0 && width !== this.width()) {
      this.zone.run(() => this.width.set(width));
    }
  }

  ngOnDestroy(): void {
    this.resize?.disconnect();
  }

  /** Programs the workbook reports entitlement for, so a gap can be drawn. */
  protected readonly programs = computed(() =>
    this.rebates().programs.map((program) => ({
      name: program.program,
      hasEligible: program.points.some((point) => point.eligibleCustomers !== null),
    })),
  );

  protected readonly selected = computed(() => {
    const wanted = this.programName() ?? this.rebates().headlineGap?.program ?? null;
    const programs = this.rebates().programs;
    return (
      programs.find((program) => program.program === wanted) ?? programs[0] ?? null
    );
  });

  private readonly points = computed<RebatePoint[]>(() => this.selected()?.points ?? []);

  private readonly plotWidth = computed(() => Math.max(80, this.width() - LEFT - RIGHT));

  private x(index: number): number {
    const count = this.points().length;
    if (count < 2) return LEFT;
    return LEFT + (index / (count - 1)) * this.plotWidth();
  }

  private readonly axis = computed(() => {
    const values = this.points().flatMap((point) =>
      [point.accounts, point.eligibleCustomers].filter(
        (value): value is number => value !== null,
      ),
    );
    return niceTicks(Math.max(1, ...values), 4);
  });

  private y(value: number): number {
    return TOP + HEIGHT - (value / this.axis().top) * HEIGHT;
  }

  protected readonly ticks = computed(() =>
    this.axis().ticks.map((value) => ({
      value,
      y: this.y(value),
      label: compact(value),
    })),
  );

  protected readonly years = computed(() =>
    this.points().map((point, index) => ({
      label: point.fy.replace('FY', ''),
      x: this.x(index),
    })),
  );

  protected readonly hasEligible = computed(() =>
    this.points().some((point) => point.eligibleCustomers !== null),
  );

  protected readonly accountsPath = computed(() =>
    polyline(
      this.points()
        .filter((point) => point.accounts !== null)
        .map((point, index) => [this.x(index), this.y(point.accounts!)]),
    ),
  );

  protected readonly eligiblePath = computed(() =>
    polyline(
      this.points()
        .filter((point) => point.eligibleCustomers !== null)
        .map((point, index) => [this.x(index), this.y(point.eligibleCustomers!)]),
    ),
  );

  /**
   * The filled gap: down the entitlement line and back along take-up.
   *
   * <p>Only drawn where both numbers exist for the same year - a program that
   * did not exist yet has no gap, and interpolating across that would invent
   * one.
   */
  protected readonly gapPath = computed(() => {
    const both = this.points()
      .map((point, index) => ({ point, index }))
      .filter(
        ({ point }) => point.accounts !== null && point.eligibleCustomers !== null,
      );
    if (both.length < 2) return '';

    const upper = both.map(({ point, index }) => `${this.x(index)},${this.y(point.eligibleCustomers!)}`);
    const lower = both
      .slice()
      .reverse()
      .map(({ point, index }) => `${this.x(index)},${this.y(point.accounts!)}`);
    return `M${upper.join(' L')} L${lower.join(' L')} Z`;
  });

  protected readonly endLabels = computed(() => {
    const points = this.points();
    const last = points.length - 1;
    if (last < 0) return null;
    const point = points[last];
    return {
      x: this.x(last) + 10,
      accounts:
        point.accounts === null
          ? null
          : { y: this.y(point.accounts) + 3, text: compact(point.accounts) },
      eligible:
        point.eligibleCustomers === null
          ? null
          : { y: this.y(point.eligibleCustomers) + 3, text: compact(point.eligibleCustomers) },
    };
  });

  protected readonly hovered = computed(() => {
    const index = this.hoverIndex();
    const points = this.points();
    if (index === null || !points[index]) return null;
    return { point: points[index], x: this.x(index) };
  });

  protected onMove(event: MouseEvent): void {
    const count = this.points().length;
    if (count < 2) return;
    const box = (event.currentTarget as SVGElement).getBoundingClientRect();
    const ratio = (event.clientX - box.left - LEFT) / this.plotWidth();
    this.hoverIndex.set(Math.max(0, Math.min(count - 1, Math.round(ratio * (count - 1)))));
  }

  protected onLeave(): void {
    this.hoverIndex.set(null);
  }

  protected choose(name: string): void {
    this.programName.set(name);
    this.hoverIndex.set(null);
  }

  protected tooltipX(x: number): number {
    return x > this.width() - 200 ? x - 190 : x + 10;
  }

  protected number(value: number | null): string {
    return value === null ? '—' : Math.round(value).toLocaleString('en-AU');
  }

  protected money(value: number | null): string {
    return value === null ? '—' : '$' + compact(value);
  }
}
