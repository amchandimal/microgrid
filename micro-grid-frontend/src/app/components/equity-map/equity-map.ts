import {
  AfterViewInit,
  Component,
  DestroyRef,
  ElementRef,
  NgZone,
  OnDestroy,
  ViewChild,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import * as L from 'leaflet';
import { catchError, of } from 'rxjs';
import { EquityMapEntry, GridStress, GridStressArea } from '../../models/council.models';
import { GridApi } from '../../services/grid-api';
import { GridRegion } from '../../models/grid.models';
import { HexGrid } from '../../services/hex-grid';

export type MapLayer = 'equity' | 'solar' | 'growth' | 'consumption' | 'grid';

/**
 * One sequential ramp, blue, light to dark.
 *
 * <p>Five steps out of the blue scale, starting at the step that still clears
 * 2:1 against a light basemap - anything lighter reads as "no data" over
 * OpenStreetMap's pale ground rather than as a low value.
 */
const SEQUENTIAL = ['#86b6ef', '#3987e5', '#256abf', '#184f95', '#0d366b'];

/**
 * The equity layer for the Illawarra map.
 *
 * <p>Suburb points, not polygons: the challenge data has no geometry at all,
 * so the boundaries do not exist to draw. Each locality is a circle sized by
 * the residential capacity actually installed there and coloured by whichever
 * layer is selected, over the same basemap and the same region extent the
 * public map uses.
 *
 * <p>The equity colouring reuses the diverging ramp already in this app
 * ({@link HexGrid}), which was chosen so red and green stay separable under
 * deuteranopia - a literal red/green pair is not. Every other layer is a
 * magnitude, so it gets one hue light-to-dark instead. Nothing is encoded by
 * colour alone: the popup names the value and the state in words.
 */
@Component({
  selector: 'app-equity-map',
  standalone: false,
  templateUrl: './equity-map.html',
  styleUrl: './equity-map.scss',
})
export class EquityMap implements AfterViewInit, OnDestroy {
  readonly localities = input.required<EquityMapEntry[]>();
  readonly gridStress = input<GridStress | null>(null);

  readonly selectLocality = output<string>();

  @ViewChild('mapHost', { static: true }) mapHost!: ElementRef<HTMLDivElement>;

  private readonly gridApi = inject(GridApi);
  private readonly hexGrid = inject(HexGrid);
  private readonly zone = inject(NgZone);
  private readonly destroyRef = inject(DestroyRef);

  private map?: L.Map;
  private markers?: L.LayerGroup;
  private hostResize?: ResizeObserver;
  private region?: GridRegion;

  protected readonly layer = signal<MapLayer>('equity');
  protected readonly loadError = signal<string | null>(null);
  protected readonly equityGradient = this.hexGrid.legendGradient();
  protected readonly sequentialSteps = SEQUENTIAL;

  constructor() {
    // Redraw whenever the layer changes or new data lands.
    effect(() => {
      this.localities();
      this.gridStress();
      this.layer();
      this.draw();
    });
  }

  ngAfterViewInit(): void {
    this.gridApi.region$
      .pipe(
        catchError(() => {
          this.loadError.set('Cannot reach the grid service on /api/grid');
          return of<GridRegion | null>(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((region) => {
        if (region) this.initMap(region);
      });
  }

  ngOnDestroy(): void {
    this.hostResize?.disconnect();
    this.map?.remove();
    this.map = undefined;
  }

  private initMap(region: GridRegion): void {
    this.region = region;
    this.map = L.map(this.mapHost.nativeElement, {
      center: [(region.north + region.south) / 2, (region.west + region.east) / 2],
      zoom: 11,
      maxBounds: region.bounds,
      maxBoundsViscosity: 1,
      zoomSnap: 0,
      scrollWheelZoom: false,
    });

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution:
        '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(this.map);

    this.markers = L.layerGroup().addTo(this.map);
    this.fit();
    this.draw();

    // The card only reaches its width after layout settles.
    if (typeof ResizeObserver !== 'undefined') {
      this.zone.runOutsideAngular(() => {
        this.hostResize = new ResizeObserver(() => {
          this.map?.invalidateSize();
          this.fit();
        });
        this.hostResize.observe(this.mapHost.nativeElement);
      });
    }
  }

  private fitted = false;

  private fit(): void {
    if (!this.map || !this.region) return;
    const size = this.map.getSize();
    if (!size.x || !size.y) return;
    if (!this.fitted) {
      this.fitted = true;
      this.map.fitBounds(this.region.bounds, { padding: [8, 8] });
    }
  }

  /** Grid readings keyed by locality, so a marker can find its own. */
  private readonly stressByLocality = computed(() => {
    const byName = new Map<string, GridStressArea>();
    this.gridStress()?.areas.forEach((area) => byName.set(area.name, area));
    return byName;
  });

  private readonly maxKw = computed(() =>
    Math.max(1, ...this.localities().map((entry) => entry.resKwAlltime)),
  );

  private draw(): void {
    if (!this.map || !this.markers) return;
    this.markers.clearLayers();

    const layer = this.layer();
    for (const entry of this.localities()) {
      const { colour, valueLabel, muted } = this.styleFor(entry, layer);
      const marker = L.circleMarker([entry.lat, entry.lng], {
        radius: this.radiusFor(entry),
        color: 'var(--mg-surface)',
        weight: 2,
        opacity: 1,
        fillColor: colour,
        fillOpacity: muted ? 0.35 : 0.9,
        // The 2px ring is in the surface colour so overlapping suburbs stay
        // legible; Leaflet cannot resolve a CSS variable, so it is literal.
      });
      marker.setStyle({ color: this.surfaceColour() });
      marker.bindPopup(this.popupFor(entry, valueLabel));
      marker.on('click', () =>
        this.zone.run(() => this.selectLocality.emit(entry.locality)),
      );
      marker.addTo(this.markers);
    }
  }

  /** Area, not radius, carries installed capacity - so a sqrt. */
  private radiusFor(entry: EquityMapEntry): number {
    return 5 + 11 * Math.sqrt(Math.max(0, entry.resKwAlltime) / this.maxKw());
  }

  private styleFor(
    entry: EquityMapEntry,
    layer: MapLayer,
  ): { colour: string; valueLabel: string; muted: boolean } {
    if (layer === 'grid') {
      const area = this.stressByLocality().get(entry.locality);
      if (!area) {
        return { colour: '#9aa4b1', valueLabel: 'No network reading', muted: true };
      }
      const colour = {
        PEAK: this.cssColour('--mg-danger', '#dc2626'),
        BALANCED: this.cssColour('--mg-warn', '#d97706'),
        SURPLUS: this.cssColour('--mg-ok', '#16a34a'),
      }[area.state];
      return {
        colour,
        valueLabel: `${this.stateLabel(area.state)} · ${area.pctDayInPeak}% of the day in peak`,
        muted: false,
      };
    }

    if (layer === 'equity') {
      if (entry.equityScore === null) {
        return { colour: '#9aa4b1', valueLabel: 'Not ranked', muted: true };
      }
      // The score is 0-100; the ramp wants -1..1 either side of the midpoint.
      const balance = (entry.equityScore - 50) / 50;
      return {
        colour: this.hexGrid.styleFor(balance).color,
        valueLabel: `Equity score ${entry.equityScore}${entry.hotspot ? ' · hotspot' : ''}`,
        muted: false,
      };
    }

    const value = this.magnitude(entry, layer);
    if (value === null) {
      return { colour: '#9aa4b1', valueLabel: 'No figure reported', muted: true };
    }
    const all = this.localities()
      .map((other) => this.magnitude(other, layer))
      .filter((v): v is number => v !== null);
    const min = Math.min(...all);
    const max = Math.max(...all);
    const step = max <= min ? 0 : Math.min(
      SEQUENTIAL.length - 1,
      Math.floor(((value - min) / (max - min)) * SEQUENTIAL.length),
    );
    return {
      colour: SEQUENTIAL[step],
      valueLabel: `${this.layerName()}: ${this.formatMagnitude(value, layer)}`,
      muted: false,
    };
  }

  private magnitude(entry: EquityMapEntry, layer: MapLayer): number | null {
    switch (layer) {
      case 'solar':
        return entry.resKwAlltime;
      case 'growth':
        return entry.resYoyPct;
      case 'consumption':
        return entry.domesticMwhPerDwelling;
      default:
        return null;
    }
  }

  private formatMagnitude(value: number, layer: MapLayer): string {
    switch (layer) {
      case 'solar':
        return `${Math.round(value).toLocaleString('en-AU')} kW`;
      case 'growth':
        return `${value}% year on year`;
      case 'consumption':
        return `${value} MWh per dwelling`;
      default:
        return String(value);
    }
  }

  private popupFor(entry: EquityMapEntry, valueLabel: string): string {
    const escape = (text: string) =>
      text.replace(/[&<>"]/g, (c) =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c] ?? c,
      );
    return `
      <div class="mg-popup">
        <strong>${escape(entry.locality)}</strong>
        <div>${entry.postcode ? escape(entry.postcode) : 'postcode not mapped'}</div>
        <div>${escape(valueLabel)}</div>
        <div>${Math.round(entry.resInstallsAlltime).toLocaleString('en-AU')} systems ·
          ${Math.round(entry.resKwAlltime).toLocaleString('en-AU')} kW</div>
        <div><em>Click for the full breakdown</em></div>
      </div>`;
  }

  protected setLayer(layer: MapLayer): void {
    this.layer.set(layer);
  }

  protected readonly layers: Array<{ id: MapLayer; label: string }> = [
    { id: 'equity', label: 'Equity score' },
    { id: 'solar', label: 'Solar kW' },
    { id: 'growth', label: 'YoY growth' },
    { id: 'consumption', label: 'Consumption' },
    { id: 'grid', label: 'Live grid stress' },
  ];

  protected layerName(): string {
    return this.layers.find((entry) => entry.id === this.layer())?.label ?? '';
  }

  protected stateLabel(state: string): string {
    return { SURPLUS: 'Surplus', BALANCED: 'Balanced', PEAK: 'Peak demand' }[state] ?? state;
  }

  protected readonly unmappedCount = computed(
    () => this.localities().filter((entry) => entry.equityScore === null).length,
  );

  private surfaceColour(): string {
    return this.cssColour('--mg-surface', '#ffffff');
  }

  private cssColour(token: string, fallback: string): string {
    const value = getComputedStyle(this.mapHost.nativeElement)
      .getPropertyValue(token)
      .trim();
    return value || fallback;
  }
}
