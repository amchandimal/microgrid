import { GridData } from './grid-data';

describe('GridData map bounds', () => {
  const data = new GridData();
  const [[south, west], [north, east]] = data.illawarraBounds;

  it('encloses every supplied corner of the zoom-out limit', () => {
    for (const [lat, lng] of data.illawarraCorners) {
      expect(lat).toBeGreaterThanOrEqual(south);
      expect(lat).toBeLessThanOrEqual(north);
      expect(lng).toBeGreaterThanOrEqual(west);
      expect(lng).toBeLessThanOrEqual(east);
    }
  });

  it('is the tightest such box - every edge is touched by a corner', () => {
    const lats = data.illawarraCorners.map(([lat]) => lat);
    const lngs = data.illawarraCorners.map(([, lng]) => lng);
    expect(south).toBe(Math.min(...lats));
    expect(north).toBe(Math.max(...lats));
    expect(west).toBe(Math.min(...lngs));
    expect(east).toBe(Math.max(...lngs));
  });

  it('is ordered [south-west, north-east] as Leaflet expects', () => {
    expect(south).toBeLessThan(north);
    expect(west).toBeLessThan(east);
  });

  it('keeps the default centre inside the limit', () => {
    const [lat, lng] = data.illawarraCenter;
    expect(lat).toBeGreaterThan(south);
    expect(lat).toBeLessThan(north);
    expect(lng).toBeGreaterThan(west);
    expect(lng).toBeLessThan(east);
  });
});
