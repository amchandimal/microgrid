export type SiteType = 'SUPPLIER' | 'HOUSEHOLD';

export interface GridSite {
  id: string;
  name: string;
  type: SiteType;
  suburb: string;
  lat: number;
  lng: number;
  /** kW capacity for suppliers, average kW draw for households */
  capacityKw: number;
  status: 'ONLINE' | 'OFFLINE' | 'STANDBY';
}

export interface PricePoint {
  hour: string;
  buy: number;
  sell: number;
}

export interface GeoResult {
  displayName: string;
  lat: number;
  lng: number;
  boundingBox?: [number, number, number, number];
}
