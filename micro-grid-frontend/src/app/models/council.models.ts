/**
 * The council dashboard's wire types.
 *
 * Mirrors `CouncilApi.java` on the backend field for field - that file is the
 * single definition of the contract, and these are the same records read from
 * the other side.
 */

export type Role = 'COUNCIL';

export interface LoginResponse {
  token: string;
  role: Role;
  displayName: string;
}

/** Where a figure came from, so the UI can show its provenance. */
export interface SourceNote {
  label: string;
  source: string;
}

/** One weighted term of the equity index. */
export interface Term {
  name: string;
  weight: number;
  source: string;
  inverted: boolean;
}

/** How the equity index is put together - shown, not paraphrased. */
export interface Methodology {
  formula: string;
  terms: Term[];
  hotspotRule: string;
  dwellingEstimate: string;
  notes: string[];
}

/** Rebate accounts against households estimated to be eligible. */
export interface RebateGap {
  program: string;
  fy: string;
  accounts: number;
  eligible: number;
  takeUpPct: number;
  missingOut: number;
}

export interface Summary {
  lga: string;
  period: string;
  totalInstallations: number;
  residentialInstallations: number;
  commercialInstallations: number;
  powerStations: number;
  housesInLga: number;
  pvDensityPct: number;
  installedResidentialKw: number;
  totalInstalledKw: number;
  avgKwhPerKwInstalled: number;
  annualSavingsAud: number;
  annualSavingsPerSystemAud: number;
  annualCo2OffsetTonnes: number;
  avgAnnualBillAud: number;
  avgGridCostCkwh: number;
  rebateGap: RebateGap | null;
  localityCount: number;
  rankedCount: number;
  hotspotCount: number;
  lgaSummary: Record<string, string>;
  sources: SourceNote[];
}

export interface Suburb {
  locality: string;
  postcode: string | null;
  resInstallsAlltime: number;
  resKwAlltime: number;
  resInstallsFy: number;
  resKwFy: number;
  resYoyPct: number | null;
  comInstallsAlltime: number;
  comKwAlltime: number;
  comInstallsFy: number;
  comKwFy: number;
  comYoyPct: number | null;
  psInstallsFy: number;
  psKwFy: number;
  /** From the all-time CSV, which runs to Dec 2025 rather than end of FY. */
  resInstallsCurrent: number | null;
  resKwCurrent: number | null;
  avgSystemKw: number | null;
  postcodeResInstalls: number | null;
  postcodeDomesticAccounts: number | null;
  /** Installations per 100 domestic accounts, at postcode level. */
  pvDensityPct: number | null;
  domesticMwhPerDwelling: number | null;
  solarScore: number | null;
  growthScore: number | null;
  consumptionScore: number | null;
  equityScore: number | null;
  rank: number | null;
  ranked: boolean;
  hotspot: boolean;
  action: string | null;
  actionReason: string | null;
  lat: number | null;
  lng: number | null;
}

export interface SuburbLeague {
  suburbs: Suburb[];
  methodology: Methodology;
  sources: SourceNote[];
}

export interface SuburbDetail {
  suburb: Suburb;
  consumption: Consumption | null;
  postcodePeers: Suburb[];
  methodology: Methodology;
}

export interface TrendPoint {
  /** ISO `yyyy-MM`. */
  month: string;
  capacityResidentialKw: number;
  capacityCommercialKw: number;
  capacityPowerStationsKw: number;
  installsResidential: number;
  installsCommercial: number;
  installsPowerStations: number;
}

/** A span of the series worth pointing at. `monthEnd` is inclusive. */
export interface TrendAnnotation {
  month: string;
  monthEnd: string;
  label: string;
  detail: string;
}

export interface Trend {
  points: TrendPoint[];
  annotations: TrendAnnotation[];
  sources: SourceNote[];
}

export interface Consumption {
  postcode: string;
  localities: string[];
  totalMwh: number;
  domesticMwh: number;
  controlledLoadMwh: number;
  commercialMwh: number;
  industrialMwh: number;
  totalAccounts: number;
  domesticAccounts: number;
  domesticMwhPerDwelling: number | null;
}

export interface ConsumptionReport {
  postcodes: Consumption[];
  headline: string;
  /** What was left out of the chart, and why. */
  note: string;
  sources: SourceNote[];
}

export interface RebatePoint {
  fy: string;
  accounts: number | null;
  paidAmountAud: number | null;
  uniqueCustomers: number | null;
  eligibleCustomers: number | null;
  takeUpPct: number | null;
}

export interface RebateProgram {
  program: string;
  points: RebatePoint[];
}

export interface EapaVouchers {
  fy: string;
  electricityApplications: number;
  gasApplications: number;
  valueAud: number;
  source: string;
}

export interface Rebates {
  financialYears: string[];
  programs: RebateProgram[];
  headlineGap: RebateGap | null;
  eapa: EapaVouchers;
  sources: SourceNote[];
}

export interface EquityMapEntry {
  locality: string;
  postcode: string | null;
  lat: number;
  lng: number;
  equityScore: number | null;
  hotspot: boolean;
  ranked: boolean;
  resInstallsAlltime: number;
  resKwAlltime: number;
  resYoyPct: number | null;
  domesticMwhPerDwelling: number | null;
  pvDensityPct: number | null;
}

export interface EquityMap {
  localities: EquityMapEntry[];
  mappedCount: number;
  localityCount: number;
  methodology: Methodology;
}

export type GridState = 'SURPLUS' | 'BALANCED' | 'PEAK';

export interface GridStressArea {
  id: string;
  name: string;
  suburb: string;
  lat: number;
  lng: number;
  state: GridState;
  exportConstrained: boolean;
  pctDayInPeak: number;
  supplyKw: number;
  demandKw: number;
  priceSignalCkwh: number;
  batterySocPct: number | null;
}

export interface GridStress {
  asOf: string;
  areas: GridStressArea[];
  peakNow: number;
  exportConstrainedNow: number;
  note: string;
}
