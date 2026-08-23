import {
  AfterViewInit,
  Component,
  DestroyRef,
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
import { GridApi } from '../../services/grid-api';
import { GridData } from '../../services/grid-data';
import { HexGrid } from '../../services/hex-grid';
import { GeoResult, GridCell, GridCells, GridRegion, GridSite } from '../../models/grid.models';

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
  private readonly gridApi = inject(GridApi);
  private readonly gridData = inject(GridData);
  private readonly hexGrid = inject(HexGrid);
  private readonly zone = inject(NgZone);
  // Held because takeUntilDestroyed() only finds one on its own inside the
  // constructor, and two of these subscriptions start later than that.
  private readonly destroyRef = inject(DestroyRef);
  private readonly query$ = new Subject<string>();
  /** Fires whenever the visible box changes and new cells are wanted. */
  private readonly viewport$ = new Subject<void>();

  private map?: L.Map;
  private searchMarker?: L.Marker;
  private siteLayer?: L.LayerGroup;
  private hexLayer?: L.LayerGroup;
  private hexRenderer?: L.Canvas;
  private selectedOutline?: L.Polygon;
  private hostResize?: ResizeObserver;
  private fitted = false;
  private settleHandle?: ReturnType<typeof setTimeout>;
  private region?: GridRegion;

  protected readonly query = signal('');
  protected readonly results = signal<GeoResult[]>([]);
  protected readonly searching = signal(false);
  protected readonly showResults = signal(false);
  protected readonly sites = this.gridData.sites;

  protected readonly showHexGrid = signal(true);
  protected readonly selectedCell = signal<GridCell | null>(null);
  protected readonly cellMetres = signal(0);
  protected readonly cellCount = signal(0);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly legendGradient = this.hexGrid.legendGradient();

  /**
   * Why the overlay stops short of the map's edges.
   *
   * <p>The camera runs Waterfall to Jervis Bay; the modelled field only covers
   * Helensburgh to Kiama. Saying so in the legend is the difference between a
   * known limit and what otherwise reads as a half-loaded map.
   */
  protected readonly coverageNote = signal<string | null>(null);

  /** What the reset button is named after - where it takes you back to. */
  protected readonly focusLabel = signal('Illawarra, NSW');

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

    // Pan and zoom both land here, several times per gesture, so coalesce and
    // let switchMap drop any request the user has already panned away from.
    this.viewport$
      .pipe(
        debounceTime(140),
        switchMap(() => {
          const request = this.currentViewport();
          if (!request) return of<GridCells | null>(null);
          this.loading.set(true);
          return this.gridApi.cells(request.bounds, request.zoom).pipe(
            catchError(() => {
              this.loadError.set('Could not load the grid overlay');
              return of<GridCells | null>(null);
            }),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((cells) => {
        this.loading.set(false);
        if (cells) this.renderCells(cells);
      });

    this.gridData.sites; // touch the signal so the sites request starts early
  }

  ngAfterViewInit(): void {
    // The region defines maxBounds and the zoom floor, so the map cannot be
    // built until the server has answered.
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
    if (this.settleHandle) clearTimeout(this.settleHandle);
    this.map?.remove();
    // The map?. guards elsewhere only mean anything if this is cleared.
    this.map = undefined;
  }

  private initMap(region: GridRegion): void {
    this.region = region;
    this.coverageNote.set(region.coverageNote ?? null);
    if (region.focus?.label) this.focusLabel.set(region.focus.label);
    this.loadError.set(null);

    this.map = L.map(this.mapHost.nativeElement, {
      // Replaced by showOpeningView() the moment the panel has a size; this
      // is only what Leaflet needs to construct itself.
      center: [region.focus.lat, region.focus.lng],
      zoom: 11,
      zoomControl: true,
      // The region is the hard outer limit of the camera: maxBounds stops the
      // pan, applyZoomOutLimit() stops the zoom.
      maxBounds: region.bounds,
      maxBoundsViscosity: 1,
      // Required, not a preference. Leaflet runs _limitZoom twice on the way
      // through setView, and with snapping on the second pass rounds the
      // fractional floor UP to the next whole level - which crops the very
      // edges this limit exists to keep on screen. Turning snapping off is the
      // only way to rest exactly on the fit. The cost is that zoom levels are
      // no longer whole numbers, so tiles are drawn rescaled from the nearest
      // level; swap this for a whole-number floor if that matters more than
      // hitting the edges exactly.
      zoomSnap: 0,
    });

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution:
        '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(this.map);

    this.fitRegion();

    // Canvas beats SVG by a wide margin at ~1000 polygons, and the pane sits
    // under the markers so site pins stay clickable through the overlay.
    this.hexRenderer = L.canvas({ padding: HEX_CANVAS_PADDING });
    this.hexLayer = L.layerGroup().addTo(this.map);
    this.map.on('moveend zoomend', () => this.viewport$.next());
    this.viewport$.next();

    this.siteLayer = L.layerGroup().addTo(this.map);
    this.drawSites();

    // The flex layout only settles after this hook, and the zoom floor is a
    // function of the container size, so track the host instead of guessing
    // at a delay.
    this.observeHostSize();
  }

  private drawSites(): void {
    if (!this.siteLayer) return;
    // Sites may land before or after the map; redraw whenever we are called.
    this.siteLayer.clearLayers();
    this.sites().forEach((site) => this.addSiteMarker(site));
    if (this.sites().length === 0) {
      // Not loaded yet - come back once the signal fills in.
      this.gridApi.sites$
        .pipe(
          catchError(() => of<GridSite[]>([])),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe((sites) => sites.forEach((site) => this.addSiteMarker(site)));
    }
  }

  protected toggleHexGrid(): void {
    this.showHexGrid.update((on) => !on);
    if (this.showHexGrid()) {
      this.viewport$.next();
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
    this.showOpeningView();
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
   * Settle the camera against the region. A host with no width yet - a hidden
   * panel, or a flex layout that has not resolved - would make fitBounds()
   * pick a whole-world view, so nothing happens until the container is real
   * and the observer calls back. The region is only fitted once; later resizes
   * just move the floor, rather than yanking the user back every time the
   * window changes.
   */
  private fitRegion(): void {
    if (!this.map || !this.region) return;

    const size = this.map.getSize();
    if (!size.x || !size.y) return;

    this.applyZoomOutLimit();
    if (!this.fitted) {
      this.fitted = true;
      this.showOpeningView();
    }
  }

  /**
   * The opening shot: Wollongong, framed by the ground that has data.
   *
   * <p>Fitting the whole region instead would park the camera out over
   * Gerringong with the top of the frame in southern Sydney - the region runs
   * a hundred and fifteen kilometres from Waterfall to Jervis Bay and the city
   * is only a slice of it. So the zoom is taken from the surveyed strip, which
   * is what the overlay actually covers, and the centre is put on the city.
   *
   * <p>This is the first frame only. The region is still the limit: the zoom
   * floor set by {@link applyZoomOutLimit} lets the whole of it be pulled into
   * view and no further, and maxBounds stops the pan at its edges.
   */
  private showOpeningView(): void {
    const region = this.region;
    if (!this.map || !region) return;

    // getBoundsZoom answers for the current window, so the frame tracks the
    // panel size rather than a zoom guessed at design time.
    const frame = region.surveyedBounds ?? region.bounds;
    const zoom = this.map.getBoundsZoom(L.latLngBounds(frame));
    this.map.setView(
      [region.focus.lat, region.focus.lng],
      Number.isFinite(zoom) ? zoom : this.map.getZoom(),
    );
  }

  /**
   * Hold the minimum zoom at the level where the region exactly fills the
   * viewport, so the map can never be pulled back past its edges. Depends on
   * the container size, so it is recomputed on every host resize.
   */
  private applyZoomOutLimit(): void {
    if (!this.map || !this.region) return;

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
    const fit = this.map.getBoundsZoom(L.latLngBounds(this.region.bounds));
    // Restore rather than leaving the map with no floor at all if it failed.
    this.map.setMinZoom(Number.isFinite(fit) ? fit : previous);
  }

  /** The box to ask the server for, padded to cover the whole hex canvas. */
  private currentViewport() {
    if (!this.map || !this.showHexGrid()) return null;
    const size = this.map.getSize();
    if (!size.x || !size.y) return null;

    // Cover the whole canvas, not just the viewport, so a pan does not expose
    // an unpainted margin before the next response lands.
    const b = this.map.getBounds().pad(HEX_CANVAS_PADDING + 0.02);
    return {
      bounds: {
        south: b.getSouth(),
        west: b.getWest(),
        north: b.getNorth(),
        east: b.getEast(),
      },
      zoom: this.map.getZoom(),
    };
  }

  private renderCells(page: GridCells): void {
    if (!this.map || !this.hexLayer || !this.showHexGrid()) return;

    // A selected cell only exists at the resolution it was picked at, so a
    // zoom that changes cell size invalidates it.
    if (page.sizeMetres !== this.cellMetres()) this.clearSelection();

    this.hexLayer.clearLayers();
    for (const cell of page.cells) {
      const ring = this.hexGrid.ringFor(cell, page.referenceLat);
      const { color, opacity } = this.hexGrid.styleFor(cell.balance);
      L.polygon(ring, {
        renderer: this.hexRenderer,
        stroke: false,
        fill: true,
        fillColor: color,
        fillOpacity: opacity,
        // Leaflet only hit-tests filled canvas shapes when interactive.
        interactive: true,
        bubblingMouseEvents: false,
      })
        .on('click', () => this.selectCell(cell, ring))
        .addTo(this.hexLayer);
    }

    this.cellMetres.set(page.sizeMetres);
    this.cellCount.set(page.count);
  }

  private selectCell(cell: GridCell, ring: [number, number][]): void {
    this.selectedOutline?.remove();
    if (this.map) {
      this.selectedOutline = L.polygon(ring, {
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
  protected cellSiteNames(cell: GridCell): string[] {
    const sites = this.sites();
    return cell.siteIds
      .map((id) => sites.find((s) => s.id === id))
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
