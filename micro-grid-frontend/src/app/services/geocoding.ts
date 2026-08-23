import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { GeoResult } from '../models/grid.models';

interface NominatimItem {
  display_name: string;
  lat: string;
  lon: string;
  boundingbox: [string, string, string, string];
}

/** OpenStreetMap Nominatim search. Public endpoint - keep request volume low. */
@Injectable({ providedIn: 'root' })
export class Geocoding {
  private readonly http = inject(HttpClient);
  private readonly url = 'https://nominatim.openstreetmap.org/search';
  private readonly reverseUrl = 'https://nominatim.openstreetmap.org/reverse';

  /**
   * A point back into a place name.
   *
   * <p>Only ever called from a press on "use my current location", so the
   * volume is one request per person who chooses it. Zoom 14 is suburb level -
   * fine enough to name where someone is, coarse enough not to read out their
   * street number.
   */
  reverse(lat: number, lng: number): Observable<GeoResult> {
    return this.http
      .get<NominatimItem>(this.reverseUrl, {
        params: { lat, lon: lng, format: 'json', zoom: '14', addressdetails: '0' },
      })
      .pipe(
        map((item) => ({
          displayName: item.display_name,
          lat: parseFloat(item.lat),
          lng: parseFloat(item.lon),
        })),
      );
  }

  search(query: string): Observable<GeoResult[]> {
    return this.http
      .get<NominatimItem[]>(this.url, {
        params: {
          q: query,
          format: 'json',
          addressdetails: '0',
          limit: '6',
          countrycodes: 'au',
        },
      })
      .pipe(
        map((items) =>
          items.map((i) => ({
            displayName: i.display_name,
            lat: parseFloat(i.lat),
            lng: parseFloat(i.lon),
            boundingBox: [
              parseFloat(i.boundingbox[0]),
              parseFloat(i.boundingbox[1]),
              parseFloat(i.boundingbox[2]),
              parseFloat(i.boundingbox[3]),
            ] as [number, number, number, number],
          })),
        ),
      );
  }
}
