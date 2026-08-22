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

/** One hexagon of the overlay, as GridController sends it. */
export interface GridCell {
  /** Stable for a given resolution: "<sizeMetres>:<q>:<r>". */
  id: string;
  q: number;
  r: number;
  /** Flat-to-flat width on the ground, in metres. */
  sizeMetres: number;
  areaHectares: number;
  lat: number;
  lng: number;
  demandKw: number;
  supplyKw: number;
  /** supplyKw - demandKw. Positive means the cell exports. */
  netKw: number;
  /** -1 all demand, 0 balanced, +1 all supply. Drives the colour. */
  balance: number;
  /** How built-up the cell is, 0..1 - the field the rest follows. */
  builtUp: number;
  siteIds: string[];
}

/** One page of the overlay. */
export interface GridCells {
  zoom: number;
  sizeMetres: number;
  /** Latitude the server sized the lattice at; needed to redraw the hexagon. */
  referenceLat: number;
  count: number;
  /** True when the server's cell cap cut the response short. */
  truncated: boolean;
  cells: GridCell[];
}

/** Extent and resolution limits, straight from the server. */
export interface GridRegion {
  /** [[south, west], [north, east]] - ready for Leaflet. */
  bounds: [[number, number], [number, number]];
  north: number;
  south: number;
  west: number;
  east: number;
  finestMetres: number;
  finestZoom: number;
  maxCells: number;
  referenceLat: number;
}
