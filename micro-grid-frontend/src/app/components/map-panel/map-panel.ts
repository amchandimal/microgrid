import {
  AfterViewInit,
  Component,
  ElementRef,
  NgZone,
  OnDestroy,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import * as L from 'leaflet';
import { Subject, debounceTime, distinctUntilChanged, switchMap, catchError, of } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Geocoding } from '../../services/geocoding';
import { GridData } from '../../services/grid-data';
import { HexGrid } from '../../services/hex-grid';
import { GeoResult, GridSite, HexCell } from '../../models/grid.models';

/** Extra ring the hex canvas paints beyond the viewport, as a fraction. */
const HEX_CANVAS_PADDING = 0.1;

@Component({
  selector: 'app-map-panel',
  standalone: false,
  templateUrl: './map-panel.html',
  styleUrl: './map-panel.scss',
})
export class MapPanel implements AfterViewInit, OnDestroy {
  @ViewChild('mapHost', { static: true }) mapHost!: ElementRef<HTMLDivElement>;

  private readonly geocoding = inject(Geocoding);
  private readonly gridData = inject(GridData);
  private readonly hexGrid = inject(HexGrid);
  private readonly zone = inject(NgZone);
  private readonly query$ = new Subject<string>();

  private map?: L.Map;
  private searchMarker?: L.Marker;
  private siteLayer?: L.LayerGroup;
  private hexLayer?: L.LayerGroup;
  private hexRenderer?: L.Canvas;
  private selectedOutline?: L.Polygon;
  private hostResize?: ResizeObserver;
  private fitted = false;
  private rebuildHandle?: ReturnType<typeof setTimeout>;
  private settleHandle?: ReturnType<typeof setTimeout>;

  protected readonly query = signal('');
  protected readonly results = signal<GeoResult[]>([]);
  protected readonly searching = signal(false);
  protected readonly showResults = signal(false);
  protected readonly sites = this.gridData.getSites();

  protected readonly showHexGrid = signal(true);
  protected readonly selectedCell = signal<HexCell | null>(null);
  protected readonly cellMetres = signal(this.hexGrid.cellMetresForZoom(this.gridData.illawarraZoom));
  protected readonly cellCount = signal(0);
  protected readonly legendGradient = this.hexGrid.legendGradient();

  constructor() {
    this.query$
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
      .subscribe((res) => {
        this.searching.set(false);
        this.results.set(res);
        this.showResults.set(res.length > 0);
      });
  }

  ngAfterViewInit(): void {
    this.map = L.map(this.mapHost.nativeElement, {
      center: this.gridData.illawarraCenter,
      zoom: this.gridData.illawarraZoom,
      zoomControl: true,
      // The Illawarra box is the hard outer limit of the camera: maxBounds
      // stops the pan, applyZoomOutLimit() stops the zoom.
      maxBounds: this.gridData.illawarraBounds,
      maxBoundsViscosity: 1,
      // Required, not a preference. Leaflet runs _limitZoom twice on the way
      // through setView, and with snapping on the second pass rounds the
      // fractional floor UP to the next whole level - which crops the very
      // corners this limit exists to keep on screen. Turning snapping off is
      // the only way to rest exactly on the fit. The cost is that zoom levels
      // are no longer whole numbers, so tiles are drawn rescaled from the
      // nearest level; swap this for a whole-number floor if that matters
      // more than hitting the corners exactly.
      zoomSnap: 0,
    });

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(this.map);

    this.fitRegion();

    // Canvas beats SVG by a wide margin at ~1000 polygons, and the pane sits
    // under the markers so site pins stay clickable through the overlay.
    this.hexRenderer = L.canvas({ padding: HEX_CANVAS_PADDING });
    this.hexLayer = L.layerGroup().addTo(this.map);
    this.map.on('moveend zoomend', () => this.scheduleHexRebuild());
    this.rebuildHexes();

    this.siteLayer = L.layerGroup().addTo(this.map);
    this.sites.forEach((site) => this.addSiteMarker(site));

    // The flex layout only settles after this hook, and the zoom floor is a
    // function of the container size, so track the host instead of guessing
    // at a delay.
    this.observeHostSize();
  }

  ngOnDestroy(): void {
    this.hostResize?.disconnect();
    if (this.rebuildHandle) clearTimeout(this.rebuildHandle);
    if (this.settleHandle) clearTimeout(this.settleHandle);
    this.map?.remove();
    // The map?. guards elsewhere only mean anything if this is cleared.
    this.map = undefined;
  }

  protected toggleHexGrid(): void {
    this.showHexGrid.update((on) => !on);
    if (this.showHexGrid()) {
      this.rebuildHexes();
    } else {
      this.hexLayer?.clearLayers();
      this.clearSelection();
      this.cellCount.set(0);
    }
  }

  protected clearSelection(): void {
    this.selectedCell.set(null);
    this.selectedOutline?.remove();
    this.selectedOutline = undefined;
  }

  /** Percentage position of a cell on the legend gradient. */
  protected legendOffset(balance: number): string {
    return `${((balance + 1) / 2) * 100}%`;
  }

  protected onQueryChange(value: string): void {
    this.query.set(value);
    this.query$.next(value);
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    const first = this.results()[0];
    if (first) {
      this.select(first);
    }
  }

  protected select(result: GeoResult): void {
    this.showResults.set(false);
    this.query.set(result.displayName);
    if (!this.map) return;

    if (result.boundingBox) {
      const [south, north, west, east] = result.boundingBox;
      this.map.fitBounds([
        [south, west],
        [north, east],
      ]);
    } else {
      this.map.setView([result.lat, result.lng], 14);
    }

    this.searchMarker?.remove();
    this.searchMarker = L.marker([result.lat, result.lng], {
      icon: this.buildIcon('search', '📍'),
    })
      .addTo(this.map)
      .bindPopup(`<strong>${result.displayName}</strong>`)
      .openPopup();
  }

  protected resetView(): void {
    this.query.set('');
    this.results.set([]);
    this.showResults.set(false);
    this.searchMarker?.remove();
    this.searchMarker = undefined;
    this.clearSelection();
    this.showWholeRegion();
  }

  private observeHostSize(): void {
    const refresh = () => {
      this.map?.invalidateSize();
      this.fitRegion();
    };

    if (typeof ResizeObserver === 'undefined') {
      // Held so teardown can cancel it - otherwise it fires on a dead map.
      this.settleHandle = setTimeout(refresh, 200);
      return;
    }

    this.zone.runOutsideAngular(() => {
      this.hostResize = new ResizeObserver(refresh);
      this.hostResize.observe(this.mapHost.nativeElement);
    });
  }

  /**
   * Settle the camera against the Illawarra box. A host with no width yet -- a
   * hidden panel, or a flex layout that has not resolved -- would make
   * fitBounds() pick a whole-world view, so nothing happens until the
   * container is real and the observer calls back. The box is only fitted
   * once; later resizes just move the floor, rather than yanking the user
   * back to the region every time the window changes.
   */
  private fitRegion(): void {
    if (!this.map) return;

    const size = this.map.getSize();
    if (!size.x || !size.y) return;

    this.applyZoomOutLimit();
    if (!this.fitted) {
      this.fitted = true;
      this.showWholeRegion();
    }
  }

/** Park the camera on the whole Illawarra box, at exactly the zoom floor. */
  private showWholeRegion(): void {
    this.map?.fitBounds(this.gridData.illawarraBounds);
  }

  /**
   * Hold the minimum zoom at the level where the Illawarra box exactly fills
   * the viewport, so the map can never be pulled back past those corners.
   * Depends on the container size, so it is recomputed on every host resize.
   */
  private applyZoomOutLimit(): void {
    if (!this.map) return;

    const size = this.map.getSize();
    if (!size.x || !size.y) return;

    // Let Leaflet measure the fit, so the floor and the fitBounds() that lands
    // on it come out of identical arithmetic - computing it by hand instead
    // left the two disagreeing in the last few decimals, which was enough to
    // shave ~19 m off the western edge. With zoomSnap 0 this returns the exact
    // fractional fit rather than rounding down to a whole level.
    //
    // getBoundsZoom() clamps its answer against the current minimum, so the
    // floor has to come off before measuring or it ratchets up on each resize.
    const previous = this.map.getMinZoom();
    this.map.setMinZoom(0);
    const fit = this.map.getBoundsZoom(L.latLngBounds(this.gridData.illawarraBounds));
    // Restore rather than leaving the map with no floor at all if it failed.
    this.map.setMinZoom(Number.isFinite(fit) ? fit : previous);
  }

  /**
   * Pan and zoom both land here, often several times per gesture, so coalesce
   * into one rebuild on the next tick.
   */
  private scheduleHexRebuild(): void {
    if (this.rebuildHandle) clearTimeout(this.rebuildHandle);
    this.rebuildHandle = setTimeout(() => this.rebuildHexes(), 90);
  }

  private rebuildHexes(): void {
    if (!this.map || !this.hexLayer || !this.showHexGrid()) return;

    const zoom = this.map.getZoom();
    const metres = this.hexGrid.cellMetresForZoom(zoom);
    // A selected cell only exists at the resolution it was picked at, so a
    // zoom that changes cell size invalidates it.
    if (metres !== this.cellMetres()) this.clearSelection();

    // Cover the whole canvas, not just the viewport, so a pan does not expose
    // an unpainted margin before the next rebuild lands.
    const cells = this.hexGrid.cellsForView(
      this.map.getBounds().pad(HEX_CANVAS_PADDING + 0.02),
      zoom,
    );

    this.hexLayer.clearLayers();
    for (const cell of cells) {
      const { color, opacity } = this.hexGrid.styleFor(cell.balance);
      L.polygon(cell.ring, {
        renderer: this.hexRenderer,
        stroke: false,
        fill: true,
        fillColor: color,
        fillOpacity: opacity,
        // Leaflet only hit-tests filled canvas shapes when interactive.
        interactive: true,
        bubblingMouseEvents: false,
      })
        .on('click', () => this.selectCell(cell))
        .addTo(this.hexLayer);
    }

    // Signals are read by the template, and pan/zoom can arrive from a
    // listener registered outside Angular, so update inside the zone.
    this.zone.run(() => {
      this.cellMetres.set(metres);
      this.cellCount.set(cells.length);
    });
  }

  private selectCell(cell: HexCell): void {
    this.selectedOutline?.remove();
    if (this.map) {
      this.selectedOutline = L.polygon(cell.ring, {
        renderer: this.hexRenderer,
        // Canvas takes a real colour - it cannot resolve a CSS variable.
        color: this.cssColour('--mg-text', '#16202c'),
        weight: 2,
        fill: false,
        interactive: false,
      }).addTo(this.map);
    }
    this.zone.run(() => this.selectedCell.set(cell));
  }

  /** Resolve a themed token to a literal colour the canvas renderer accepts. */
  private cssColour(token: string, fallback: string): string {
    const value = getComputedStyle(this.mapHost.nativeElement)
      .getPropertyValue(token)
      .trim();
    return value || fallback;
  }

  /** Site names for the selected cell, for the readout card. */
  protected cellSiteNames(cell: HexCell): string[] {
    return cell.siteIds
      .map((id) => this.sites.find((s) => s.id === id))
      .filter((s): s is GridSite => !!s)
      .map((s) => s.name);
  }

  private addSiteMarker(site: GridSite): void {
    if (!this.siteLayer) return;
    const isSupplier = site.type === 'SUPPLIER';
    const marker = L.marker([site.lat, site.lng], {
      icon: this.buildIcon(isSupplier ? 'supplier' : 'household', isSupplier ? '⚡' : '🏠'),
    });
    marker.bindPopup(`
      <div class="mg-popup">
        <strong>${site.name}</strong>
        <div>${site.suburb}</div>
        <div>${isSupplier ? 'Supplier' : 'Household'} &middot; ${site.capacityKw} kW</div>
        <div>Status: ${site.status}</div>
      </div>
    `);
    marker.addTo(this.siteLayer);
  }

  private buildIcon(kind: string, glyph: string): L.DivIcon {
    return L.divIcon({
      className: '',
      html: `<div class="mg-marker mg-marker--${kind}">${glyph}</div>`,
      iconSize: [28, 28],
      iconAnchor: [14, 14],
      popupAnchor: [0, -16],
    });
  }
}
