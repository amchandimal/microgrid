import { Injectable } from '@angular/core';
import * as L from 'leaflet';
import { GridCell } from '../models/grid.models';

/**
 * Draws the hexagon overlay. The numbers come from GridController - what is
 * left here is turning a cell into a shape and a colour.
 */
@Injectable({ providedIn: 'root' })
export class HexGrid {
  /**
   * The six corners of a cell, rebuilt from its centre.
   *
   * The server sends centres rather than rings, because six coordinate pairs
   * per cell would be most of the payload. `referenceLat` is the latitude it
   * sized the lattice at, so both ends draw the same hexagon.
   */
  ringFor(cell: GridCell, referenceLat: number): [number, number][] {
    const width = cell.sizeMetres / Math.cos((referenceLat * Math.PI) / 180);
    const radius = width / Math.sqrt(3);
    const centre = L.Projection.SphericalMercator.project(L.latLng(cell.lat, cell.lng));

    const ring: [number, number][] = [];
    for (let i = 0; i < 6; i++) {
      const angle = ((60 * i - 30) * Math.PI) / 180;
      const corner = L.Projection.SphericalMercator.unproject(
        L.point(centre.x + radius * Math.cos(angle), centre.y + radius * Math.sin(angle)),
      );
      ring.push([corner.lat, corner.lng]);
    }
    return ring;
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

type RGB = [number, number, number];

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
