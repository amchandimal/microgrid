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

// --- Energy Outcome Wizard ----------------------------------------------------

export type GridState = 'SURPLUS' | 'BALANCED' | 'PEAK';

export interface CommunityBattery {
  name: string;
  suburb: string;
  lat: number;
  lng: number;
  capacityKwh: number;
}

/** Live supply and demand around one address. */
export interface GridStatus {
  area: string;
  lat: number;
  lng: number;
  timestamp: string;
  cellCount: number;
  supplyKw: number;
  demandKw: number;
  surplusKw: number;
  state: GridState;
  exportConstrained: boolean;
  priceSignalCkwh: number;
  communityBatterySocPct: number | null;
  communityBattery: CommunityBattery | null;
}

export type Role = 'RENTER' | 'HOMEOWNER' | 'LANDLORD';
export type HomeType = 'HOUSE' | 'APARTMENT' | 'TOWNHOUSE' | 'SINGLE_DWELLING' | 'MULTI_UNIT';
export type Answer = 'YES' | 'NO' | 'NOT_SURE';
export type Presence = 'OFTEN' | 'SOMETIMES' | 'RARELY';
export type Upfront = 'NO_UPFRONT' | 'OPEN_TO_INVESTMENT';

export interface WizardAnswers {
  address: string;
  suburb: string;
  lat: number;
  lng: number;
  role: Role | null;
  homeType: HomeType | null;
  smartMeter: Answer | null;
  daytimePresence: Presence | null;
  concessionCard: boolean;
  upfrontPreference: Upfront | null;
  bigAppliances: boolean;
  hasSolar: Answer | null;
  solarKw: number | null;
  hasBattery: Answer | null;
  wantsToSellSurplus: boolean;
  openToSubsidisedSolar: boolean;
  wouldOfferSolarToTenants: boolean;
}

export interface Recommendation {
  id: string;
  title: string;
  summary: string;
  annualLow: number;
  annualHigh: number;
  upfrontCost: number;
  effort: 'ONE_TAP' | 'SHORT' | 'INVOLVED';
  timing: 'THIS_WEEK' | 'THIS_MONTH';
  howTo: string;
  source: string;
  free: boolean;
}

/** "Your Maximum Outcome Plan". */
export interface OutcomePlan {
  suburb: string;
  role: Role;
  totalAnnualLow: number;
  totalAnnualHigh: number;
  zeroUpfrontCount: number;
  doThisWeek: Recommendation[];
  doThisMonth: Recommendation[];
  grid: GridStatus;
  gridTip: string;
  maximiseTips: string[];
  findOutNext: string[];
}

// --- Real installed fleet, apportioned to areas -------------------------------

/** The LGA totals, straight out of the two Total System exports. */
export interface GridTotals {
  residentialInstalls: number;
  commercialInstalls: number;
  powerStationInstalls: number;
  totalInstalls: number;
  residentialKw: number;
  commercialKw: number;
  powerStationKw: number;
  totalKw: number;
  avgResidentialSystemKw: number;
  areaCount: number;
}

/** One area's share of the modelled field, and the systems it earns. */
export interface GridArea {
  id: string;
  name: string;
  postcode: string | null;
  lat: number;
  lng: number;
  cellCount: number;
  supplyKw: number;
  demandKw: number;
  supplySharePct: number;
  demandSharePct: number;
  residentialInstalls: number;
  commercialInstalls: number;
  powerStationInstalls: number;
  residentialKw: number;
  commercialKw: number;
  powerStationKw: number;
}

export interface GridInstallations {
  /** ISO months, e.g. "2001-01 to 2025-12". */
  period: string;
  totals: GridTotals;
  areas: GridArea[];
  /** How the areas were apportioned - shown, not paraphrased. */
  method: string;
  sources: string[];
}
