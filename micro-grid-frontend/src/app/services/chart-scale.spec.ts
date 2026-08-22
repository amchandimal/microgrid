import { compact, monthLabel, niceTicks, polyline } from './chart-scale';

describe('Axis ticks', () => {
  it('rounds the axis up to a step the reader can do arithmetic with', () => {
    const { ticks, top } = niceTicks(2295, 3);
    expect(top).toBe(3000);
    expect(ticks).toEqual([0, 1000, 2000, 3000]);
  });

  it('walks the 1-2-5 ladder rather than dividing the maximum', () => {
    expect(niceTicks(408, 3).ticks).toEqual([0, 200, 400, 600]);
    expect(niceTicks(22, 3).ticks).toEqual([0, 10, 20, 30]);
    expect(niceTicks(1_181_300, 4).top).toBe(1_500_000);
  });

  it('always leaves the top mark clear of the frame', () => {
    for (const max of [1, 7, 99, 101, 2295, 933_000]) {
      expect(niceTicks(max).top).toBeGreaterThanOrEqual(max);
    }
  });

  it('survives an empty or degenerate series', () => {
    expect(niceTicks(0).ticks).toEqual([0, 1]);
    expect(niceTicks(-5).ticks).toEqual([0, 1]);
    expect(niceTicks(NaN).ticks).toEqual([0, 1]);
  });
});

describe('Compact numbers', () => {
  it('keeps small numbers whole and grouped', () => {
    expect(compact(0)).toBe('0');
    expect(compact(408)).toBe('408');
    expect(compact(1284)).toBe('1,284');
  });

  it('switches to k and M where a label would otherwise not fit', () => {
    expect(compact(12_900)).toBe('12.9k');
    expect(compact(911_200)).toBe('911.2k');
    expect(compact(1_181_300)).toBe('1.2M');
  });
});

describe('Month labels', () => {
  it('reads an ISO month back as a person would say it', () => {
    expect(monthLabel('2001-01')).toBe('Jan 2001');
    expect(monthLabel('2025-12')).toBe('Dec 2025');
    expect(monthLabel('2025-07')).toBe('Jul 2025');
  });
});

describe('Polyline points', () => {
  it('emits SVG-ready pairs, rounded so the attribute stays short', () => {
    expect(
      polyline([
        [0, 10],
        [1.23456, 20.98765],
      ]),
    ).toBe('0,10 1.23,20.99');
  });
});
