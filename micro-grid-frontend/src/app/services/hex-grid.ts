import { Injectable, inject } from '@angular/core';
import * as L from 'leaflet';
import { GridData } from './grid-data';
import { GridSite, HexCell } from '../models/grid.models';

/**
 * Builds the hexagon demand/supply overlay.
 *
 * The finest resolution is 50 m across the flats, but the Illawarra box is
 * 81 x 50 km: tiling all of it at 50 m would take ~1.86 million hexagons, and
 * below zoom 16 a 50 m hexagon is under a pixel wide. So cells are generated
 * for the visible viewport only, and the resolution halves with every zoom
 * level until it bottoms out at the requested 50 m from zoom 16 in. That keeps
 * roughly a thousand hexagons on screen at any zoom, each about 25 px across.
 */
@Injectable({ providedIn: 'root' })
export class HexGrid {
  private readonly gridData = inject(GridData);

  /** The finest cell, flat-to-flat, in metres. */
  readonly finestMetres = 50;
  /** Zoom at which cells reach {@link finestMetres}. */
  readonly finestZoom = 16;
  /**
   * Ceiling on one pass. A 4K viewport needs roughly 4000, so this leaves
   * headroom; hitting it is reported rather than silently cropping the map.
   */
  readonly maxCells = 9000;
  /** Set when the last pass hit {@link maxCells}. */
  truncated = false;

  private readonly sites = this.gridData.getSites();

  /** Ground metres across the flats for a given zoom. */
  cellMetresForZoom(zoom: number): number {
    const steps = Math.max(0, Math.ceil(this.finestZoom - zoom));
    return this.finestMetres * Math.pow(2, steps);
  }

  /**
   * Every cell overlapping `bounds`, at the resolution implied by `zoom`.
   * Cells are laid out in the Web Mercator plane so they stay regular on
   * screen; across this region that costs under 1% in true ground size.
   */
  cellsForView(bounds: L.LatLngBounds, zoom: number): HexCell[] {
    const metres = this.cellMetresForZoom(zoom);
    const midLat = (bounds.getNorth() + bounds.getSouth()) / 2;
    // Mercator exaggerates distance by 1/cos(lat); undo it so the hexagon is
    // `metres` wide on the ground rather than on the projection.
    const width = metres / Math.cos((midLat * Math.PI) / 180);
    const radius = width / Math.sqrt(3);
    const rowStep = 1.5 * radius;

    const sw = this.project(bounds.getSouthWest());
    const ne = this.project(bounds.getNorthEast());

    const rMin = Math.floor(sw.y / rowStep) - 1;
    const rMax = Math.ceil(ne.y / rowStep) + 1;

    const cells: HexCell[] = [];
    this.truncated = false;
    for (let r = rMin; r <= rMax; r++) {
      const y = r * rowStep;
      const xOffset = (r & 1) === 0 ? 0 : width / 2;
      const qMin = Math.floor((sw.x - xOffset) / width) - 1;
      const qMax = Math.ceil((ne.x - xOffset) / width) + 1;
      for (let q = qMin; q <= qMax; q++) {
        if (cells.length >= this.maxCells) {
          this.truncated = true;
          return cells;
        }
        cells.push(this.buildCell(q, r, q * width + xOffset, y, radius, metres));
      }
    }
    return cells;
  }

  /** Fill colour and opacity for a cell, from the validated diverging ramp. */
  styleFor(balance: number): { color: string; opacity: number } {
    const t = Math.max(-1, Math.min(1, balance));
    const magnitude = Math.abs(t);
    const pole = t < 0 ? DEMAND_POLE : SUPPLY_POLE;
    const poleAlpha = t < 0 ? DEMAND_ALPHA : SUPPLY_ALPHA;
    return {
      color: mixOklab(NEUTRAL, pole, magnitude),
      opacity: NEUTRAL_ALPHA + (poleAlpha - NEUTRAL_ALPHA) * magnitude,
    };
  }

  /** CSS gradient for the legend, sampled off the same ramp. */
  legendGradient(): string {
    const stops: string[] = [];
    for (let i = 0; i <= 10; i++) {
      stops.push(`${this.styleFor(-1 + (2 * i) / 10).color} ${i * 10}%`);
    }
    return `linear-gradient(90deg, ${stops.join(', ')})`;
  }

  private buildCell(
    q: number,
    r: number,
    x: number,
    y: number,
    radius: number,
    metres: number,
  ): HexCell {
    const ring: [number, number][] = [];
    for (let i = 0; i < 6; i++) {
      const angle = ((60 * i - 30) * Math.PI) / 180;
      const corner = this.unproject(
        x + radius * Math.cos(angle),
        y + radius * Math.sin(angle),
      );
      ring.push([corner.lat, corner.lng]);
    }

    const centre = this.unproject(x, y);
    const areaHectares = ((Math.sqrt(3) / 2) * metres * metres) / 10000;
    const load = this.loadAt(centre.lat, centre.lng, areaHectares);

    const half = metres / 2;
    const siteIds = this.sites
      .filter((s) => this.groundDistance(s, centre.lat, centre.lng) <= half)
      .map((s) => s.id);

    const total = load.demandKw + load.supplyKw;
    return {
      id: `${metres}:${q}:${r}`,
      q,
      r,
      sizeMetres: metres,
      areaHectares: round(areaHectares, 3),
      center: [centre.lat, centre.lng],
      ring,
      demandKw: round(load.demandKw, 1),
      supplyKw: round(load.supplyKw, 1),
      netKw: round(load.supplyKw - load.demandKw, 1),
      balance: total > 0 ? round((load.supplyKw - load.demandKw) / total, 3) : 0,
      siteIds,
    };
  }

  /**
   * Stand-in for real telemetry: a smooth background field for rooftop load,
   * plus a gaussian footprint around each known site. Both are densities in
   * kW per hectare, so a cell's totals scale with its area and a coarse cell
   * reads as the sum of the fine cells inside it.
   */
  private loadAt(lat: number, lng: number, areaHectares: number) {
    const x = lng * 92000; // rough metres - only has to be smooth and stable
    const y = lat * 111000;

    // Value noise clusters around its own midpoint, which would leave most of
    // the map sitting on the neutral part of the ramp, so stretch it first.
    const houses = contrast(fbm(x, y, 1600));
    const roofs = contrast(fbm(x + 9000, y - 4000, 2400));

    // Generation headroom runs mostly against housing density - the denser the
    // dwellings, the less roof and open land per unit of load - with the rest
    // left to its own field so the two are not a perfect mirror.
    const headroom = ROOF_ANTICORRELATION * (1 - houses) + (1 - ROOF_ANTICORRELATION) * roofs;

    let demandDensity = BACKGROUND_FLOOR + BACKGROUND_RANGE * houses;
    let supplyDensity = BACKGROUND_FLOOR + BACKGROUND_RANGE * headroom;

    for (const site of this.sites) {
      if (site.status === 'OFFLINE') continue;
      const supplier = site.type === 'SUPPLIER';
      const sigma = supplier ? SUPPLIER_SIGMA : HOUSEHOLD_SIGMA;
      const distance = this.groundDistance(site, lat, lng);
      const weight = Math.exp(-((distance / sigma) ** 2));
      if (weight < 0.002) continue;

      const footprintHa = (Math.PI * sigma * sigma) / 10000;
      const load = supplier ? site.capacityKw : site.capacityKw * HOUSEHOLD_CLUSTER;
      const density = (load / footprintHa) * weight;
      const derated = site.status === 'STANDBY' ? density * 0.35 : density;

      if (supplier) supplyDensity += derated;
      else demandDensity += derated;
    }

    return {
      demandKw: demandDensity * areaHectares,
      supplyKw: supplyDensity * areaHectares,
    };
  }

  private groundDistance(site: GridSite, lat: number, lng: number): number {
    const dLat = (site.lat - lat) * 110950;
    const dLng = (site.lng - lng) * 111320 * Math.cos((lat * Math.PI) / 180);
    return Math.hypot(dLat, dLng);
  }

  private project(latlng: L.LatLng): L.Point {
    return L.Projection.SphericalMercator.project(latlng);
  }

  private unproject(x: number, y: number): L.LatLng {
    return L.Projection.SphericalMercator.unproject(L.point(x, y));
  }
}

// --- diverging ramp ----------------------------------------------------------
// Red (demand) <-> neutral <-> green (supply). The poles composite over OSM
// land to #e47270 / #58caa2, which clears the colour-vision gates: deuteranopia
// dE 8.2 (>= 8 target) and normal vision dE 26.2 (>= 15 floor). The green pole
// is emerald rather than the app's --mg-ok green because a literal red/green
// pair measures 4.8 under deuteranopia and is not separable at all.
const DEMAND_POLE: RGB = [0.863, 0.149, 0.149]; // #dc2626, the app's danger red
const SUPPLY_POLE: RGB = [0.063, 0.725, 0.506]; // #10b981
const NEUTRAL: RGB = [0.941, 0.937, 0.925]; // #f0efec, a hueless midpoint
const NEUTRAL_ALPHA = 0.16;
const DEMAND_ALPHA = 0.62;
const SUPPLY_ALPHA = 0.68;

// Background rooftop load, kW per hectare. Demand and supply share a floor and
// a range so a cell with no site near it is as likely to be green as red.
const BACKGROUND_FLOOR = 1;
const BACKGROUND_RANGE = 8;

/** How hard to stretch the noise away from its midpoint. */
const FIELD_CONTRAST = 2;
/** Share of generation headroom driven by the inverse of housing density. */
const ROOF_ANTICORRELATION = 0.7;

/** How far a site's influence reaches. Generation carries further than load. */
const SUPPLIER_SIGMA = 1800;
const HOUSEHOLD_SIGMA = 900;

/**
 * Each household entry is a neighbourhood ("Figtree Estate"), not one meter,
 * so its listed draw stands in for this many dwellings.
 */
const HOUSEHOLD_CLUSTER = 25;

type RGB = [number, number, number];

const round = (value: number, places: number) => {
  const factor = Math.pow(10, places);
  const rounded = Math.round(value * factor) / factor;
  // Math.round can hand back -0, which renders as "-0 kW" in the readout.
  return rounded === 0 ? 0 : rounded;
};

const toLinear = (c: number) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
const toSrgb = (c: number) => {
  const v = Math.max(0, Math.min(1, c));
  return v <= 0.0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - 0.055;
};

function toOklab([r, g, b]: RGB): RGB {
  const lr = toLinear(r);
  const lg = toLinear(g);
  const lb = toLinear(b);
  const l = Math.cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb);
  const m = Math.cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb);
  const s = Math.cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

function fromOklab([lightness, a, b]: RGB): RGB {
  const l = (lightness + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (lightness - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (lightness - 0.0894841775 * a - 1.291485548 * b) ** 3;
  return [
    toSrgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
    toSrgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
    toSrgb(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s),
  ];
}

/** Interpolate in Oklab so the ramp keeps an even perceptual step. */
function mixOklab(from: RGB, to: RGB, t: number): string {
  const a = toOklab(from);
  const b = toOklab(to);
  const mixed = fromOklab([
    a[0] + (b[0] - a[0]) * t,
    a[1] + (b[1] - a[1]) * t,
    a[2] + (b[2] - a[2]) * t,
  ]);
  return `#${mixed
    .map((v) => Math.round(Math.max(0, Math.min(1, v)) * 255).toString(16).padStart(2, '0'))
    .join('')}`;
}

// --- deterministic background field ------------------------------------------
function hash2(x: number, y: number): number {
  let h = Math.imul(x, 374761393) + Math.imul(y, 668265263);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

function valueNoise(x: number, y: number, scale: number): number {
  const gx = x / scale;
  const gy = y / scale;
  const x0 = Math.floor(gx);
  const y0 = Math.floor(gy);
  const fx = gx - x0;
  const fy = gy - y0;
  const sx = fx * fx * (3 - 2 * fx);
  const sy = fy * fy * (3 - 2 * fy);
  const top = hash2(x0, y0) * (1 - sx) + hash2(x0 + 1, y0) * sx;
  const bottom = hash2(x0, y0 + 1) * (1 - sx) + hash2(x0 + 1, y0 + 1) * sx;
  return top * (1 - sy) + bottom * sy;
}

/** Two octaves - enough texture to read as a real load map. */
function fbm(x: number, y: number, scale: number): number {
  return valueNoise(x, y, scale) * 0.65 + valueNoise(x, y, scale / 3) * 0.35;
}

/** Push a 0..1 field away from its midpoint, still clamped to 0..1. */
function contrast(v: number): number {
  return Math.max(0, Math.min(1, (v - 0.5) * FIELD_CONTRAST + 0.5));
}
