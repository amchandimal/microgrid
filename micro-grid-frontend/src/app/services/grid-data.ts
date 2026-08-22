import { Injectable } from '@angular/core';
import { GridSite, PricePoint } from '../models/grid.models';

/**
 * Dummy data source for the dashboard. Swap the method bodies for HTTP calls
 * to micro-grid-backend when the API is ready.
 */
@Injectable({ providedIn: 'root' })
export class GridData {
  /** Approx. centre of the Illawarra region, NSW (Wollongong). */
  readonly illawarraCenter: [number, number] = [-34.5, 150.85];
  readonly illawarraZoom = 10;

  /** Bounds roughly covering Helensburgh -> Kiama / Shellharbour. */
  readonly illawarraBounds: [[number, number], [number, number]] = [
    [-34.85, 150.55],
    [-34.13, 151.05],
  ];

  getSites(): GridSite[] {
    return [
      // --- Energy suppliers ---
      { id: 'S-01', name: 'Port Kembla Solar Farm', type: 'SUPPLIER', suburb: 'Port Kembla', lat: -34.4783, lng: 150.9022, capacityKw: 4200, status: 'ONLINE' },
      { id: 'S-02', name: 'Illawarra Wind Co-op', type: 'SUPPLIER', suburb: 'Dapto', lat: -34.4986, lng: 150.7947, capacityKw: 2650, status: 'ONLINE' },
      { id: 'S-03', name: 'Wollongong Rooftop Pool', type: 'SUPPLIER', suburb: 'Wollongong', lat: -34.4278, lng: 150.8931, capacityKw: 1875, status: 'ONLINE' },
      { id: 'S-04', name: 'Shellharbour Battery Hub', type: 'SUPPLIER', suburb: 'Shellharbour', lat: -34.5806, lng: 150.8697, capacityKw: 3100, status: 'STANDBY' },
      { id: 'S-05', name: 'Kiama Community Energy', type: 'SUPPLIER', suburb: 'Kiama', lat: -34.6708, lng: 150.8542, capacityKw: 980, status: 'ONLINE' },
      { id: 'S-06', name: 'Bulli Hydro Micro-Plant', type: 'SUPPLIER', suburb: 'Bulli', lat: -34.3383, lng: 150.9161, capacityKw: 640, status: 'OFFLINE' },

      // --- Households ---
      { id: 'H-101', name: 'Fairy Meadow Cluster', type: 'HOUSEHOLD', suburb: 'Fairy Meadow', lat: -34.3944, lng: 150.8983, capacityKw: 42, status: 'ONLINE' },
      { id: 'H-102', name: 'Figtree Estate', type: 'HOUSEHOLD', suburb: 'Figtree', lat: -34.4383, lng: 150.8536, capacityKw: 65, status: 'ONLINE' },
      { id: 'H-103', name: 'Corrimal Terraces', type: 'HOUSEHOLD', suburb: 'Corrimal', lat: -34.3778, lng: 150.9044, capacityKw: 38, status: 'ONLINE' },
      { id: 'H-104', name: 'Albion Park Rail Homes', type: 'HOUSEHOLD', suburb: 'Albion Park Rail', lat: -34.5697, lng: 150.7906, capacityKw: 54, status: 'STANDBY' },
      { id: 'H-105', name: 'Thirroul Beachside', type: 'HOUSEHOLD', suburb: 'Thirroul', lat: -34.3153, lng: 150.9236, capacityKw: 29, status: 'ONLINE' },
      { id: 'H-106', name: 'Warrawong Village', type: 'HOUSEHOLD', suburb: 'Warrawong', lat: -34.4881, lng: 150.8869, capacityKw: 47, status: 'ONLINE' },
      { id: 'H-107', name: 'Helensburgh North', type: 'HOUSEHOLD', suburb: 'Helensburgh', lat: -34.1786, lng: 150.9964, capacityKw: 22, status: 'OFFLINE' },
      { id: 'H-108', name: 'Berkeley Green', type: 'HOUSEHOLD', suburb: 'Berkeley', lat: -34.4667, lng: 150.8492, capacityKw: 51, status: 'ONLINE' },
    ];
  }

  /** Buying / selling price in cents per kWh across the trading day. */
  getPriceCurve(): PricePoint[] {
    return [
      { hour: '00', buy: 21.4, sell: 8.1 },
      { hour: '03', buy: 19.8, sell: 7.4 },
      { hour: '06', buy: 24.6, sell: 9.2 },
      { hour: '09', buy: 27.9, sell: 11.6 },
      { hour: '12', buy: 22.3, sell: 14.8 },
      { hour: '15', buy: 25.1, sell: 13.2 },
      { hour: '18', buy: 38.7, sell: 19.4 },
      { hour: '21', buy: 29.5, sell: 12.7 },
    ];
  }

  getBuyPrice(): number {
    return 32.45;
  }

  getSellPrice(): number {
    return 14.80;
  }
}
