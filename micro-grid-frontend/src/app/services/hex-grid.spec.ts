import { HexGrid } from './hex-grid';
import { GridCell } from '../models/grid.models';

function cell(partial: Partial<GridCell> = {}): GridCell {
  return {
    id: '50:1:2', q: 1, r: 2, sizeMetres: 50, areaHectares: 0.217,
    lat: -34.4278, lng: 150.8931, demandKw: 1, supplyKw: 1, netKw: 0,
    balance: 0, builtUp: 0.5, siteIds: [], ...partial,
  };
}

describe('HexGrid geometry', () => {
  const grid = new HexGrid();
  const referenceLat = -34.4203264;

  it('builds a six-sided ring around the cell centre', () => {
    const ring = grid.ringFor(cell(), referenceLat);
    expect(ring.length).toBe(6);
    const lats = ring.map(([lat]) => lat);
    const lngs = ring.map(([, lng]) => lng);
    expect((Math.min(...lats) + Math.max(...lats)) / 2).toBeCloseTo(-34.4278, 4);
    expect((Math.min(...lngs) + Math.max(...lngs)) / 2).toBeCloseTo(150.8931, 4);
  });

  it('makes the hexagon the width the server said, on the ground', () => {
    const c = cell({ sizeMetres: 50 });
    const ring = grid.ringFor(c, referenceLat);
    const lngs = ring.map(([, lng]) => lng);
    const metres =
      (Math.max(...lngs) - Math.min(...lngs)) * 111320 * Math.cos((c.lat * Math.PI) / 180);
    expect(metres).toBeGreaterThan(49);
    expect(metres).toBeLessThan(51);
  });

  it('scales with the cell size', () => {
    const small = grid.ringFor(cell({ sizeMetres: 50 }), referenceLat);
    const big = grid.ringFor(cell({ sizeMetres: 800 }), referenceLat);
    const width = (r: [number, number][]) =>
      Math.max(...r.map(([, l]) => l)) - Math.min(...r.map(([, l]) => l));
    expect(width(big) / width(small)).toBeCloseTo(16, 1);
  });
});

describe('HexGrid colour ramp', () => {
  const grid = new HexGrid();

  it('puts a hueless midpoint between the two poles', () => {
    const mid = grid.styleFor(0);
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(mid.color.slice(i, i + 2), 16));
    expect(Math.max(r, g, b) - Math.min(r, g, b)).toBeLessThan(12);
  });

  it('runs red for demand and green for supply', () => {
    const demand = grid.styleFor(-1).color;
    const supply = grid.styleFor(1).color;
    expect(parseInt(demand.slice(1, 3), 16)).toBeGreaterThan(parseInt(demand.slice(3, 5), 16) + 60);
    expect(parseInt(supply.slice(3, 5), 16)).toBeGreaterThan(parseInt(supply.slice(1, 3), 16) + 60);
  });

  it('fades toward transparent as a cell approaches balance', () => {
    expect(grid.styleFor(0).opacity).toBeLessThan(grid.styleFor(0.5).opacity);
    expect(grid.styleFor(0.5).opacity).toBeLessThan(grid.styleFor(1).opacity);
    expect(grid.styleFor(0).opacity).toBeLessThan(grid.styleFor(-1).opacity);
  });

  it('clamps out-of-range balances to the poles', () => {
    expect(grid.styleFor(-4).color).toBe(grid.styleFor(-1).color);
    expect(grid.styleFor(4).color).toBe(grid.styleFor(1).color);
  });

  it('emits a legend gradient spanning both poles', () => {
    const gradient = grid.legendGradient();
    expect(gradient.startsWith('linear-gradient(90deg,')).toBe(true);
    expect(gradient).toContain(grid.styleFor(-1).color);
    expect(gradient).toContain(grid.styleFor(1).color);
  });
});
