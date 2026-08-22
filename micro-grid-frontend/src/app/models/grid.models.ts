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

/** One hexagon of the demand/supply overlay. */
export interface HexCell {
  /** Stable across pans at a given resolution: "<sizeMetres>:<q>:<r>". */
  id: string;
  /** Axial coordinates in the hex lattice. */
  q: number;
  r: number;
  /** Flat-to-flat width on the ground, in metres. */
  sizeMetres: number;
  areaHectares: number;
  center: [number, number];
  /** The six corners, ready for L.polygon. */
  ring: [number, number][];
  demandKw: number;
  supplyKw: number;
  /** supplyKw - demandKw. Positive means the cell exports. */
  netKw: number;
  /** -1 = pure demand (red) .. 0 = balanced .. +1 = pure supply (green). */
  balance: number;
  /** Sites whose footprint falls inside this cell. */
  siteIds: string[];
}
