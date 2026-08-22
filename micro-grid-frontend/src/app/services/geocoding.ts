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
