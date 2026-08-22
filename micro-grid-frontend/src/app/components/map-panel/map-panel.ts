import {
  AfterViewInit,
  Component,
  ElementRef,
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
import { GeoResult, GridSite } from '../../models/grid.models';

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
  private readonly query$ = new Subject<string>();

  private map?: L.Map;
  private searchMarker?: L.Marker;
  private siteLayer?: L.LayerGroup;

  protected readonly query = signal('');
  protected readonly results = signal<GeoResult[]>([]);
  protected readonly searching = signal(false);
  protected readonly showResults = signal(false);
  protected readonly sites = this.gridData.getSites();

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
    });

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(this.map);

    this.map.fitBounds(this.gridData.illawarraBounds);

    this.siteLayer = L.layerGroup().addTo(this.map);
    this.sites.forEach((site) => this.addSiteMarker(site));

    // Leaflet needs a nudge once the flex layout has settled.
    setTimeout(() => this.map?.invalidateSize(), 200);
  }

  ngOnDestroy(): void {
    this.map?.remove();
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
    this.map?.fitBounds(this.gridData.illawarraBounds);
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
