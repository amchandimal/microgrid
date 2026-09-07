# G.E.D — Geospatial Energy Decision Platform

An entry for the **Energy Equity Challenge** (Wollongong City Council, Hack the Gong 2026).

Wollongong has 27,773 rooftop solar systems and 189,737 kW of installed capacity, but the
benefit is not spread evenly: the data Council publishes stops at postcode level, and the
people least able to pay a power bill are the least likely to have a roof they can put panels
on. This project turns Council's spreadsheets into two things you can act on:

| For | What it is | Where |
|---|---|---|
| **Residents** — renters, apartment dwellers, homeowners, landlords | A live map of local supply and demand, plus a three-question wizard that produces a ranked "Maximum Outcome Plan" of what each person can actually save or earn | `/` and `/grid-smart` |
| **Council** | A password-protected equity dashboard: every locality scored 0–100 on how well rooftop solar serves it, hotspots ranked worst-first, twenty-five years of growth, consumption by postcode, the rebate take-up gap, and live network stress | `/council` |

The design constraint that shaped the whole thing: **a renter in a flat with no roof has to
come out of the wizard with a real number.** Nothing asks about income, "not sure" is always a
valid answer, and options that cost nothing up front are ranked on the same footing as
anything a homeowner gets.

---

## Repository layout

```
micro-grid/
├── micro-grid-backend/        Spring Boot 4.1 / Java 21 REST API + SQLite store
├── micro-grid-frontend/       Angular 22 SPA, Leaflet map, hand-rolled SVG charts
├── Challenge/                 The Energy Equity Challenge data drop, as supplied
├── Council-Dashboard-Instructions.md   Build spec for the council dashboard
├── Energy-Wizard-Spec.md      Build spec for the wizard, with sources for every figure
└── .claude/launch.json        Dev-server definitions for both apps
```

Each sub-project has its own README with the detail:

- **[micro-grid-backend/README.md](micro-grid-backend/README.md)** — API reference, the data
  pipeline, the equity index, the grid model, configuration and deployment.
- **[micro-grid-frontend/README.md](micro-grid-frontend/README.md)** — routes, components,
  services, environments, accessibility and the chart layer.

---

## Prerequisites

| | Version | Notes |
|---|---|---|
| JDK | 21 | The Maven wrapper (`mvnw`) fetches Maven itself |
| Node.js | 20+ (developed on 24) | |
| npm | 11+ | `packageManager` is pinned to `npm@11.17.0` |

No database to install — the backend builds its own SQLite file on first start. No API keys are
required to run the app; the optional `NREL_API_KEY` only enables one extra endpoint.

## Quick start

Two terminals. Backend first — the frontend calls it on port 8080.

```bash
cd micro-grid-backend && ./mvnw spring-boot:run
```

```bash
cd micro-grid-frontend && npm install && npm start
```

Then open **http://localhost:4200**.

On Windows use `mvnw.cmd` instead of `./mvnw`. Both apps are also defined in
`.claude/launch.json`, so a Claude Code session can start either by name.

### First run

The backend creates `micro-grid-backend/microgrid.db` on startup and loads the Energy Equity
Challenge spreadsheets into it. That takes a few seconds once; every later start reads the
existing file. The database is gitignored — **delete it to force a reload** after changing a
data file or a loader.

### Signing in as Council

The `/council` dashboard is behind a demo login:

```
username: council
password: Wollongong2026!
```

This is deliberately hackathon-grade auth — one fixed account and one fixed token — because the
dashboard is gated to keep suburb-level disadvantage rankings off the open web, not because
there is a user directory to authenticate against. Both credentials are properties with
defaults (`council.auth.username` / `council.auth.password`), so a real deployment overrides
them without a rebuild. See `CouncilAuth.java`.

> **These credentials are public.** They are in this repository, which means they are not a
> secret and never were. Any deployment reachable from the internet **must** set
> `COUNCIL_USERNAME`, `COUNCIL_PASSWORD` and `COUNCIL_TOKEN` in its environment — otherwise the
> defaults above are the live login. The token is also fixed and unexpiring, so treat it as a
> demo mechanism rather than a session: it is the right size for a hackathon dashboard and the
> wrong size for anything holding real personal data. Swapping in a real identity provider is a
> change to `CouncilAuth` and the interceptor beside it, and nothing else.

---

## Architecture

```
                    Browser (Angular 22, port 4200)
                                 │
        ┌────────────────────────┼────────────────────────┐
        │                        │                        │
   Public map + wizard    Council dashboard        Nominatim (OSM)
    /  ·  /grid-smart         /council              address search
        │                        │
        └───────────┬────────────┘
                    │  HTTP + JSON (CORS in dev, same-origin in prod)
                    ▼
         Spring Boot 4.1 API (port 8080)
        ┌───────────┴─────────────────────────────┐
        │                                         │
  Modelled grid                             Loaded data
  GridService — hexagon field over          EnergyDataLoader — 6 CSV/XLSX
  the Illawarra, shaped by a built-up       files parsed once into SQLite
  density lattice                           (microgrid.db)
        │                                         │
  GridAllocationService — shares the        EquityIndexService — scores
  real 27,773-system fleet across the       every locality 0–100 and flags
  map's areas by supply and demand          the bottom quartile
        │                                         │
  EnergyWizardService — eligibility →       CouncilDashboardService — joins,
  rank by $ → Maximum Outcome Plan          derives, attaches a citation to
                                            every figure
```

Two ideas hold this together:

1. **The grid model and the real data are joined, not confused.** `GridService` builds a
   modelled supply/demand field over the region — it knows *where* energy moves but not how
   many systems are behind it. The Council exports know the real totals but nothing about
   where they are. `GridAllocationService` apportions the real fleet across the modelled areas
   and writes the result to SQLite, so no request ever touches a CSV.

2. **Every number on the council dashboard carries its source.** Figures that are not in the
   loaded files (average bill $1,521/yr, grid cost 35.8 c/kWh) are constants with the table
   they came from attached, and `GET /api/council/sources` renders the provenance footer.

## The equity index

Per locality, 0–100, higher = better served. Three min-maxed terms, weighted as the brief sets
them out:

```
equity = 100 × (0.5 · solar_density + 0.2 · growth + 0.3 · (1 − household_consumption))
```

Consumption is inverted: high use with low solar is a household under pressure, not one doing
well. The bottom quartile is flagged as a hotspot.

The denominator is the honest part. The challenge data has installations by *suburb* and
electricity accounts by *postcode*, and no dwelling count in between. Splitting a postcode's
accounts across its suburbs was tried and thrown out — it produced densities over 200%. So
density is computed at the level the data actually supports: residential installations in a
postcode over that postcode's domestic accounts, carried by every suburb in it, with suburbs
separated by their own growth rate. Nothing is estimated, and the methodology travels with
every API response so the dashboard states it on screen.

---

## Data

The `Challenge/Energy Equity Challenge/` drop is copied verbatim into
`micro-grid-backend/src/main/resources/data/`, original filenames intact, so a refreshed drop
from Council can be dropped straight in.

| File | Loaded into |
|---|---|
| `Installations+Capacity by Suburb - FY 24-25.csv` | `lga_summary`, `suburb_solar` |
| `Installations+Capacity by suburb - All Time.csv` | `suburb_solar_current` |
| `Total System Capacity - Wollongong - All Time.csv` | `monthly_capacity` |
| `Total System Installations - Wollongong - All Time.csv` | `monthly_installations` |
| `Wollongong LGA electricity consumption 21-22.xlsx` | `postcode_consumption` |
| `NSW_Energy_Social_Programs_..._Trends_Analysis.xlsx` | `rebate_trend` |

Two seed resources bridge gaps the source files leave: `illawarra/suburb-seed.csv` maps every
locality to a postcode and an approximate centre (the source files carry no geometry at all),
and `illawarra/built-up-density.properties` is the lattice that shapes the map overlay.

## Tests

```bash
cd micro-grid-backend && ./mvnw test
```

```bash
cd micro-grid-frontend && npm test
```

158 backend tests (JUnit 5 + Spring web slices) and 31 frontend tests (Vitest + jsdom).
Backend tests never call Google or the NREL API — reCAPTCHA is forced off for the test JVM by
the surefire configuration in `pom.xml`, and the weather service is driven against stubs.

> **Known failure:** `src/app/app.spec.ts > should offer the Grid Smart wizard in the nav`
> still asserts an "Overview" link in the top nav that the "Removed Overview" commit deleted
> from `app.html`. The route itself still exists and the brand logo links to it. The other 30
> frontend tests pass.

## Deployment

Both apps ship as containers. The frontend is a static nginx image with an SPA fallback; the
backend is a fat jar on Amazon Corretto 21.

```bash
cd micro-grid-frontend && npm run build && docker build -t microgrid-frontend .
```

```bash
cd micro-grid-backend && ./mvnw package && docker build -t microgrid-backend .
```

Run the backend with `SPRING_PROFILES_ACTIVE=prod`, which reads every setting from the
environment: `MICRO_GRID_ALLOWED_ORIGINS`, `COUNCIL_USERNAME` / `COUNCIL_PASSWORD` /
`COUNCIL_TOKEN`, `MICRO_GRID_DB` (point it at the container's `/home` volume so the database
survives a restart), and the `RECAPTCHA_*` group. The production frontend build is a
compile-time swap of `environment.prod.ts`, so its API origin and reCAPTCHA switch need a
rebuild rather than an environment variable.

reCAPTCHA Enterprise is wired in front of the whole API but **off in both halves** — the
deployment has no Google Cloud API key yet, and turning on one side alone refuses every
request. See `application-prod.properties` for exactly what to set when it goes back on.

## Sources

The pitch figures and the policy each recommendation points at are cited in
[Energy-Wizard-Spec.md](Energy-Wizard-Spec.md) — Solar Sharer (NSW, from 1 Jul 2026), Solar
Banks, Solar for Apartment Residents, Endeavour Energy's Illawarra community batteries, the
Haystacks solar-garden model, and the Endeavour DAPR export-constraint data.
