# micro-grid-frontend

The browser half of [G.E.D](../README.md) — Angular 22, Leaflet, and a set of hand-drawn SVG
charts. It serves two audiences from one app: residents get a live map of their local network
and a wizard that tells them what they can save, and Council gets a password-protected equity
dashboard behind the same shell.

Talks to [micro-grid-backend](../micro-grid-backend/README.md) on port 8080.

---

## Running it

```bash
npm install
npm start
```

Opens on **http://localhost:4200** with hot reload. The backend has to be running on port 8080 —
`ng serve` no longer proxies, so `environment.ts` names the API origin absolutely and the
backend allows `http://localhost:4200` by CORS under its `dev` profile. Serve this on another
port and `micro-grid.cors.allowed-origins` on the backend has to follow.

| Command | What it does |
|---|---|
| `npm start` | Dev server, development configuration |
| `npm run build` | Production build into `dist/micro-grid-frontend/` |
| `npm run build:dev` | Unminified build with source maps |
| `npm run watch` | Development build, rebuilt on change |
| `npm test` | Vitest, once |

The production build currently comes in at **671 kB raw / 164 kB transferred** initial total,
against budgets of 800 kB warn and 2 MB error.

---

## Routes

| Path | Component | Guard | What it is |
|---|---|---|---|
| `/` | `Overview` | — | The Leaflet map and the public grid dashboard, side by side |
| `/grid-smart` | `GridSmart` | — | The Energy Outcome Wizard |
| `/login/council` | `CouncilLogin` | — | Two fields, the plainest form in the app |
| `/council` | `CouncilDashboard` | `councilGuard` | The equity dashboard |
| `**` | — | — | Redirects to `/` |

The top nav shows **How to Be Grid Smart** plus either a **Login** link or, once signed in, the
**Council dashboard** link and a sign-out button.

`councilGuard` is a courtesy, not the security boundary — it only decides what the browser
renders. The data itself is refused by the backend without a bearer token, which is where the
real check lives.

---

## Project structure

```
src/
├── main.ts                       bootstraps AppModule
├── styles.scss                   global tokens: surfaces, text, chart series, dark mode
├── environments/
│   ├── environment.model.ts      the shape both files must satisfy
│   ├── environment.ts            dev — API on localhost:8080, reCAPTCHA off
│   └── environment.prod.ts       prod — same-origin API, swapped in at build time
└── app/
    ├── app.ts / app.html         the shell: brand, nav, router outlet
    ├── app-routing-module.ts
    ├── app-module.ts             declarations + HttpClient with both interceptors
    ├── core/api.ts               apiUrl(), apiPathOf(), recaptchaActionFor()
    ├── models/
    │   ├── grid.models.ts        map, wizard and geocoding wire types
    │   └── council.models.ts     mirrors CouncilApi.java field for field
    ├── services/
    │   ├── grid-api.ts           /api/grid — region, cells, sites, status
    │   ├── wizard-api.ts         /api/wizard/plan
    │   ├── council-api.ts        /api/council, cached
    │   ├── grid-data.ts          shared dashboard state
    │   ├── hex-grid.ts           cell → Leaflet polygon and colour
    │   ├── chart-scale.ts        the arithmetic all four charts share
    │   ├── geocoding.ts          OpenStreetMap Nominatim
    │   ├── auth.ts               the council session
    │   └── recaptcha.ts          the slice of grecaptcha.enterprise used
    ├── guards/council-guard.ts
    ├── interceptors/
    │   ├── council-token-interceptor.ts   Bearer token, council calls only
    │   └── recaptcha-interceptor.ts       X-Recaptcha-Token on every /api call
    ├── components/
    │   ├── map-panel/            Leaflet map, hex overlay, address search
    │   ├── dashboard-panel/      public fleet figures and area list
    │   ├── equity-map/           the council choropleth
    │   ├── trend-chart/          capacity and installs, 2001–2025
    │   ├── consumption-chart/    MWh by postcode, stacked
    │   └── rebate-chart/         take-up against entitlement
    └── pages/
        ├── overview/             map + dashboard
        ├── grid-smart/           the wizard
        ├── council-login/
        └── council-dashboard/    seven panels
```

This is a **module-based** app, not standalone components — `AppModule` declares everything and
the CLI schematics are configured to match (`"standalone": false`).

---

## The map

`MapPanel` owns a Leaflet map and three layers: the OSM basemap, site markers, and the hexagon
overlay drawn on a `L.Canvas` renderer.

The overlay is fetched per viewport. Every pan and zoom pushes onto a subject that debounces
and `switchMap`s, so a fast drag issues one request rather than forty, and cells arrive already
sized for the current zoom — the backend halves the resolution with every level out. Cells
carry no geometry beyond a centre and an axial `q`/`r`; `HexGrid` rebuilds the six corners
client-side from `sizeMetres` and the `referenceLat` the API sends, which keeps the payload to
a fraction of what shipping rings would cost.

`/api/grid/region` supplies the bounds, the resolution limits, the opening camera and the
**surveyed sub-area** — the modelled overlay covers Helensburgh to Kiama while the camera
covers the whole region, and the map says so rather than leaving the gap looking like a
loading failure.

Address search goes to Nominatim directly, debounced, `au`-restricted and capped at six
results. Reverse geocoding is used only behind "use my current location", at zoom 14 — fine
enough to name a suburb, coarse enough not to read back a street number.

## The wizard

`GridSmart` is three steps — location, role, questions — and then a plan.

The point is that a renter in a flat with no roof comes out of it with real numbers, so the
no-roof branches are first-class rather than a fallback. Nothing asks about income; it asks
about concession cards and frames them as unlocking extra benefits. "Not sure" is always an
answer, and every unknown becomes a "find this out" follow-up on the plan instead of a dead
end.

The interface carries the same argument, because this is the page someone is meant to get
through on a phone in bright sun:

- Every answer is a large tile with its own icon, label, supporting line and tick — **nothing
  is said with colour alone.** `tone` names a slot; the stylesheet owns the hex, and every tone
  clears 4.5:1 on the card.
- One polite live region for the whole page, so step moves, search results and the build queue
  instead of talking over each other.
- Focus moves to the new step's heading on every move.
- Nothing required is enforced by a dead disabled button — the missing answers are listed.
- Text size (three steps) and higher contrast are on the page itself, remembered in
  `localStorage`.

## The council dashboard

Seven panels answering one question: where does Council spend the next Solar Banks round.

KPI strip · equity choropleth · hotspot list · ranked league table with a sortable equity score
· growth trend 2001–2025 · consumption by postcode · rebate take-up · live grid, refreshed
every 60 s.

Every panel that reports a derived number carries where it came from, and the score breakdown
is shown on the suburb detail drawer rather than being a black box.

The six file-backed endpoints are **cached for the life of the page** — they are last financial
year's spreadsheets and cannot change while the dashboard is open, and several panels read the
same one. `gridStress()` is the exception and is refetched every time.

## Charts

There is no chart library in this project and none is added for four charts. `chart-scale.ts`
is the handful of functions that stand between raw numbers and an SVG — `niceTicks`, `compact`,
`monthLabel`, `polyline`, plot insets — kept in one place so every chart rounds its axis,
formats its values and measures its plot area the same way.

Series colours are three fixed categorical slots in `styles.scss`, never cycled and never
reassigned by rank, validated on this app's own surfaces: worst adjacent pair is aqua/orange at
deuteranopia ΔE 8.4, all three at or above 3:1 contrast. `equity-map` uses a five-step
sequential blue ramp that starts at the step still clearing 2:1 against a light basemap —
anything lighter reads as "no data" over OpenStreetMap's pale ground.

The rebate chart is worth a note: two lines and the space between them, and **the gap is the
only thing filled**, because the gap is the finding — households entitled to a rebate that are
not receiving one. Both series are counts of customers, so they share one axis. There is no
second scale and there should never be one.

---

## Environments and configuration

`environment.model.ts` declares the shape both files must satisfy, so the production file
cannot drift from the development one — `ng build --configuration production` swaps the file,
not the type, and a key added to one and forgotten in the other is a compile error rather than
an `undefined` found in the browser.

| | `environment.ts` (dev) | `environment.prod.ts` |
|---|---|---|
| `apiBaseUrl` | `http://localhost:8080` | `https://sustainalens.com` |
| `recaptcha.enabled` | `false` | `false` |

Both are **compile-time constants**, not environment variables — changing the API origin or
turning reCAPTCHA on means a rebuild. `apiBaseUrl` carries no trailing slash and no `/api`
suffix; the services append their own paths through `apiUrl()`.

In production the app is served from the same origin as the API, so the browser makes
same-origin calls and the reverse proxy routes `/api` to the backend. A split `api.` / `www.`
deployment only needs this constant and the backend's `micro-grid.cors.allowed-origins`
changed together.

## Interceptors

Both are registered on one `HttpClient`, which is also what talks to Nominatim — so both are
scoped carefully.

**`councilTokenInterceptor`** attaches the bearer token to `/api/council/` and to nothing else.
A session token has no business being sent to a third party. `apiPathOf()` in `core/api.ts` is
what decides whose URL a request is, and it accepts the configured origin, the page's own
origin, and a relative `/api/...` — so it keeps working now that the base URL is absolute in
both configurations.

**`recaptchaInterceptor`** puts a token in `X-Recaptcha-Token` on every `/api` call, because the
backend verifies every URL in production. `recaptchaActionFor()` derives one coarse action per
API area (plus `LOGIN`), and the backend's `RecaptchaActions` derives the same string from the
same path — **the two have to change together.**

While `recaptcha.enabled` is false nothing loads `enterprise.js` and no header is sent, which
matches a backend whose reCAPTCHA interceptor is only registered under `prod`. Turning one side
on without the other refuses every request.

## The council session

`Auth` holds `{ token, role, displayName }` in a signal and mirrors it into `localStorage` so a
reload does not sign the user out mid-demo. There is no refresh and no expiry. That is the
right trade for a token granting read access to one dashboard; it would not be for a real
credential. Every storage access is wrapped — private browsing can refuse it, and the
in-memory signal still works.

---

## Tests

```bash
npm test
```

Vitest with jsdom, 31 tests across `app.spec.ts`, `core/api.spec.ts`,
`interceptors/council-token-interceptor.spec.ts`, `services/chart-scale.spec.ts` and
`services/hex-grid.spec.ts` — the pure logic, which is where the bugs that matter live.

> **Known failure:** `app.spec.ts > should offer the Grid Smart wizard in the nav` still asserts
> an "Overview" link in the top nav that the "Removed Overview" commit deleted from `app.html`.
> The other 30 pass. Either drop the assertion or put the link back, depending on which was
> intended.

## Style budgets

`angular.json` sets `anyComponentStyle` to **warn at 14 kB, error at 20 kB**.
`grid-smart.scss` currently builds to 17.75 kB and warns; everything else is well under. If you
add to it, take something out — the wizard's stylesheet is the one to watch.

## Docker

```bash
npm run build
docker build -t microgrid-frontend .
docker run -p 8080:80 microgrid-frontend
```

`nginx:alpine` serving `dist/micro-grid-frontend/browser` with gzip on and
`try_files $uri $uri/ /index.html` so client-side routes survive a refresh. The image is static —
the API origin was baked in at build time, so a retargeted deployment needs a rebuild.
