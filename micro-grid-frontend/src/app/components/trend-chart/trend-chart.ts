import {
  AfterViewInit,
  Component,
  ElementRef,
  NgZone,
  OnDestroy,
  ViewChild,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { Trend, TrendAnnotation, TrendPoint } from '../../models/council.models';
import { compact, monthLabel, niceTicks, polyline } from '../../services/chart-scale';

/** One plotted series, ready for the template. */
interface Series {
  key: string;
  label: string;
  colour: string;
  path: string;
  last: number;
}

/** One of the two stacked panels. */
interface Panel {
  title: string;
  unit: string;
  height: number;
  series: Series[];
  ticks: Array<{ value: number; y: number; label: string }>;
}

const LEFT = 46;
const RIGHT = 12;
const PANEL_HEIGHT = 132;
const PANEL_GAP = 34;
const TOP = 10;
const AXIS_HEIGHT = 22;

/**
 * Rooftop solar added to the Wollongong grid, month by month, 2001 to 2025.
 *
 * <p>Two panels rather than one: kilowatts and system counts are different
 * units and putting them on one plot would need a second y-axis, which invents
 * a correlation out of where the two scales happen to line up. They share an
 * x-axis and a hover, so reading them together still works - a month where
 * capacity climbs but installations do not is systems getting bigger, and that
 * is visible here precisely because the axes are separate.
 *
 * <p>Power stations stay on the chart even though the line barely leaves the
 * floor. Nineteen of them in twenty-five years against twenty-six thousand
 * rooftops is the point being made.
 */
@Component({
  selector: 'app-trend-chart',
  standalone: false,
  templateUrl: './trend-chart.html',
  styleUrl: './trend-chart.scss',
})
export class TrendChart implements AfterViewInit, OnDestroy {
  readonly trend = input.required<Trend>();

  @ViewChild('host', { static: true }) host!: ElementRef<HTMLDivElement>;

  private readonly zone = inject(NgZone);
  private resize?: ResizeObserver;

  /** Measured, so one SVG unit is one CSS pixel and a 2px stroke is 2px. */
  protected readonly width = signal(720);
  protected readonly hoverIndex = signal<number | null>(null);

  protected readonly totalHeight =
    TOP + PANEL_HEIGHT * 2 + PANEL_GAP + AXIS_HEIGHT;
  protected readonly left = LEFT;

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

  /**
   * Measure once here as well as on resize.
   *
   * <p>ResizeObserver callbacks are delivered as part of the rendering steps,
   * so a chart in a tab that is not painting - a background tab, a headless
   * browser - would otherwise sit at its placeholder width forever, and the
   * CSS would scale the viewBox rather than the chart being drawn to fit.
   * Reading the box here costs one synchronous layout and does not care
   * whether frames are being produced.
   */
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

  protected readonly points = computed(() => this.trend().points);

  private readonly plotWidth = computed(() => Math.max(120, this.width() - LEFT - RIGHT));

  /** x for a point index. One month per step, evenly spaced. */
  private x(index: number): number {
    const points = this.points();
    if (points.length < 2) return LEFT;
    return LEFT + (index / (points.length - 1)) * this.plotWidth();
  }

  protected readonly capacityPanel = computed(() =>
    this.panel(
      'Capacity added',
      'kW per month',
      TOP,
      [
        ['capacityResidentialKw', 'Residential', 'var(--mg-series-1)'],
        ['capacityCommercialKw', 'Commercial', 'var(--mg-series-2)'],
        ['capacityPowerStationsKw', 'Power stations', 'var(--mg-series-3)'],
      ],
    ),
  );

  protected readonly installsPanel = computed(() =>
    this.panel(
      'Systems installed',
      'systems per month',
      TOP + PANEL_HEIGHT + PANEL_GAP,
      [
        ['installsResidential', 'Residential', 'var(--mg-series-1)'],
        ['installsCommercial', 'Commercial', 'var(--mg-series-2)'],
        ['installsPowerStations', 'Power stations', 'var(--mg-series-3)'],
      ],
    ),
  );

  /** Panel tops, so the template can place the frames without arithmetic. */
  protected readonly capacityTop = TOP;
  protected readonly installsTop = TOP + PANEL_HEIGHT + PANEL_GAP;
  protected readonly panelHeight = PANEL_HEIGHT;

  private panel(
    title: string,
    unit: string,
    top: number,
    columns: Array<[keyof TrendPoint, string, string]>,
  ): Panel {
    const points = this.points();
    const max = Math.max(
      1,
      ...points.flatMap((point) => columns.map(([key]) => Number(point[key]))),
    );
    const { ticks, top: axisTop } = niceTicks(max, 3);
    const y = (value: number) => top + PANEL_HEIGHT - (value / axisTop) * PANEL_HEIGHT;

    return {
      title,
      unit,
      height: PANEL_HEIGHT,
      series: columns.map(([key, label, colour]) => ({
        key: String(key),
        label,
        colour,
        path: polyline(
          points.map((point, index) => [this.x(index), y(Number(point[key]))]),
        ),
        last: Number(points[points.length - 1]?.[key] ?? 0),
      })),
      ticks: ticks.map((value) => ({ value, y: y(value), label: compact(value) })),
    };
  }

  /** A tick every five years, plus whichever year the series starts in. */
  protected readonly yearTicks = computed(() => {
    const points = this.points();
    const marks: Array<{ x: number; label: string }> = [];
    points.forEach((point, index) => {
      const [year, month] = point.month.split('-');
      if (month !== '01') return;
      if (Number(year) % 5 !== 0 && index !== 0) return;
      marks.push({ x: this.x(index), label: year });
    });
    return marks;
  });

  /** The shaded band the backend asks us to point at, projected to pixels. */
  protected readonly bands = computed(() => {
    const points = this.points();
    return this.trend().annotations.flatMap((annotation: TrendAnnotation) => {
      const from = points.findIndex((point) => point.month === annotation.month);
      const to = points.findIndex((point) => point.month === annotation.monthEnd);
      if (from < 0 || to < 0) return [];
      return [
        {
          x: this.x(from),
          width: Math.max(3, this.x(to) - this.x(from)),
          label: annotation.label,
          detail: annotation.detail,
        },
      ];
    });
  });

  // --- hover ----------------------------------------------------------------

  protected readonly hovered = computed(() => {
    const index = this.hoverIndex();
    const points = this.points();
    if (index === null || !points[index]) return null;
    return { index, point: points[index], x: this.x(index) };
  });

  protected onMove(event: MouseEvent): void {
    const points = this.points();
    if (points.length < 2) return;
    const box = (event.currentTarget as SVGElement).getBoundingClientRect();
    const offset = event.clientX - box.left - LEFT;
    const ratio = offset / this.plotWidth();
    const index = Math.round(ratio * (points.length - 1));
    this.hoverIndex.set(Math.max(0, Math.min(points.length - 1, index)));
  }

  protected onLeave(): void {
    this.hoverIndex.set(null);
  }

  /** Tooltip x, flipped to the left of the crosshair near the right edge. */
  protected tooltipX(x: number): number {
    return x > this.width() - 190 ? x - 178 : x + 10;
  }

  protected label(month: string): string {
    return monthLabel(month);
  }

  protected format(value: number): string {
    return Math.round(value).toLocaleString('en-AU');
  }
}
