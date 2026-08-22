import * as L from 'leaflet';
import { HexGrid } from './hex-grid';
import { GridData } from './grid-data';

/**
 * HexGrid only injects GridData, so build it by hand rather than standing up
 * a TestBed for one dependency.
 */
function makeHexGrid(): HexGrid {
  const grid = Object.create(HexGrid.prototype) as HexGrid;
  const data = new GridData();
  Object.assign(grid, {
    gridData: data,
    finestMetres: 50,
    finestZoom: 16,
    maxCells: 9000,
    truncated: false,
    sites: data.getSites(),
  });
  return grid;
}

describe('HexGrid resolution', () => {
  const grid = makeHexGrid();

  it('bottoms out at the requested 50 m from zoom 16 in', () => {
    expect(grid.cellMetresForZoom(16)).toBe(50);
    expect(grid.cellMetresForZoom(17)).toBe(50);
    expect(grid.cellMetresForZoom(19)).toBe(50);
  });

  it('doubles the cell for every zoom level out', () => {
    expect(grid.cellMetresForZoom(15)).toBe(100);
    expect(grid.cellMetresForZoom(14)).toBe(200);
    expect(grid.cellMetresForZoom(13)).toBe(400);
    expect(grid.cellMetresForZoom(12)).toBe(800);
  });

  it('never shrinks below 50 m on a fractional zoom', () => {
    for (let z = 10; z <= 19; z += 0.25) {
      expect(grid.cellMetresForZoom(z)).toBeGreaterThanOrEqual(50);
    }
  });
});

describe('HexGrid geometry', () => {
  const grid = makeHexGrid();
  // A small window near Wollongong, at the finest resolution.
  const bounds = L.latLngBounds([-34.435, 150.885], [-34.425, 150.9]);
  const cells = grid.cellsForView(bounds, 16);

  it('produces cells without hitting the safety cap', () => {
    expect(cells.length).toBeGreaterThan(0);
    expect(grid.truncated).toBe(false);
  });

  it('builds closed six-sided rings', () => {
    for (const cell of cells.slice(0, 40)) {
      expect(cell.ring.length).toBe(6);
    }
  });

  it('makes hexagons 50 m across the flats on the ground', () => {
    const cell = cells[Math.floor(cells.length / 2)];
    const lngs = cell.ring.map(([, lng]) => lng);
    const widthDegrees = Math.max(...lngs) - Math.min(...lngs);
    const metres =
      widthDegrees * 111320 * Math.cos((cell.center[0] * Math.PI) / 180);
    expect(metres).toBeGreaterThan(49);
    expect(metres).toBeLessThan(51);
  });

  it('keeps demand, supply and balance consistent', () => {
    for (const cell of cells.slice(0, 60)) {
      expect(cell.demandKw).toBeGreaterThan(0);
      expect(cell.supplyKw).toBeGreaterThan(0);
      expect(cell.balance).toBeGreaterThanOrEqual(-1);
      expect(cell.balance).toBeLessThanOrEqual(1);
      // netKw and balance must agree on which way the cell leans, except
      // where rounding has flattened netKw to zero.
      if (cell.netKw !== 0) {
        expect(Math.sign(cell.netKw)).toBe(Math.sign(cell.balance));
      }
      expect(Object.is(cell.netKw, -0)).toBe(false);
    }
  });

  it('is deterministic - the same view yields the same numbers', () => {
    const again = grid.cellsForView(bounds, 16);
    expect(again.length).toBe(cells.length);
    expect(again[0].id).toBe(cells[0].id);
    expect(again[0].balance).toBe(cells[0].balance);
  });
});

describe('HexGrid colour ramp', () => {
  const grid = makeHexGrid();

  it('puts a hueless midpoint between the two poles', () => {
    const mid = grid.styleFor(0);
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(mid.color.slice(i, i + 2), 16));
    expect(Math.max(r, g, b) - Math.min(r, g, b)).toBeLessThan(12);
  });

  it('runs red for demand and green for supply', () => {
    const demand = grid.styleFor(-1).color;
    const supply = grid.styleFor(1).color;
    const red = parseInt(demand.slice(1, 3), 16);
    const redGreen = parseInt(demand.slice(3, 5), 16);
    expect(red).toBeGreaterThan(redGreen + 60);

    const greenRed = parseInt(supply.slice(1, 3), 16);
    const green = parseInt(supply.slice(3, 5), 16);
    expect(green).toBeGreaterThan(greenRed + 60);
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
