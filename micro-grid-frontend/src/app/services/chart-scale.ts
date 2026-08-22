/**
 * The arithmetic every chart in the council dashboard shares.
 *
 * <p>No chart library is in this project and none is added for four charts -
 * these are the handful of functions that stand between raw numbers and an
 * SVG, kept in one place so every chart rounds its axis, formats its values
 * and measures its plot area the same way.
 */

/** Inner padding of a plot, in CSS pixels. */
export interface Insets {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

/**
 * Axis ticks on round numbers.
 *
 * <p>An axis labelled 0 / 573 / 1,146 is arithmetically correct and unreadable.
 * This walks up 1-2-5 x 10^n until the steps are round and there are about as
 * many as asked for, then returns the ticks and the top of the axis - which is
 * the rounded-up maximum, never the data's exact maximum, so the highest mark
 * does not touch the frame.
 */
export function niceTicks(max: number, target = 4): { ticks: number[]; top: number } {
  if (!isFinite(max) || max <= 0) {
    return { ticks: [0, 1], top: 1 };
  }
  const rough = max / target;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const normalised = rough / magnitude;
  const step =
    (normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 5 ? 5 : 10) * magnitude;

  const top = Math.ceil(max / step) * step;
  const ticks: number[] = [];
  for (let value = 0; value <= top + step / 2; value += step) {
    ticks.push(Math.round(value * 1e6) / 1e6);
  }
  return { ticks, top };
}

/** 1,284 · 12.9k · 4.2M - the stat-tile convention, used on axes too. */
export function compact(value: number): string {
  const magnitude = Math.abs(value);
  if (magnitude >= 1_000_000) return trim(value / 1_000_000) + 'M';
  if (magnitude >= 10_000) return trim(value / 1000) + 'k';
  return Math.round(value).toLocaleString('en-AU');
}

function trim(value: number): string {
  return (Math.round(value * 10) / 10).toString();
}

/** "2001-07" as it should be read aloud. */
export function monthLabel(isoMonth: string): string {
  const [year, month] = isoMonth.split('-');
  const names = [
    'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
  ];
  return `${names[Number(month) - 1] ?? month} ${year}`;
}

/** An SVG polyline `points` attribute from already-projected coordinates. */
export function polyline(points: Array<[number, number]>): string {
  return points.map(([x, y]) => `${round(x)},${round(y)}`).join(' ');
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
