# micro-grid-backend

The REST API behind [G.E.D](../README.md) — Spring Boot 4.1 on Java 21, with the Energy Equity
Challenge data in SQLite beside it.

It does three things:

1. **Models the Illawarra network.** A hexagon field of supply, demand and headroom over the
   region, built per viewport, and Wollongong's real 27,773-system rooftop fleet apportioned
   across it.
2. **Serves the Energy Outcome Wizard.** Answers in, a ranked plan of what a person can save or
   earn out — filtered by eligibility, sorted by dollars, with the live grid state folded into
   the advice.
3. **Serves the council equity dashboard.** Six spreadsheets loaded once into SQLite, joined
   and scored, with a citation attached to every figure.

---

## Running it

```bash
./mvnw spring-boot:run
```

On Windows, `mvnw.cmd spring-boot:run`. The app listens on **http://localhost:8080** and starts
under the `dev` profile, which allows CORS from `http://localhost:4200` and leaves reCAPTCHA
off.

```bash
./mvnw test
```

158 tests. Nothing in the suite calls Google or the NREL API.

```bash
./mvnw package
```

Produces `target/backend-0.0.1-SNAPSHOT.jar`.

### First start

`EnergyDataLoader` creates `microgrid.db` in the working directory and fills it from the
spreadsheets on the classpath. It is **idempotent per table** — the schema is created if
absent and each table is filled only when empty — so a restart costs nothing.

**Delete `microgrid.db` to force a reload** after editing a data file or a loader. The one
exception is the suburb-to-postcode seed, which is rewritten on every start because it is code
rather than data.

The file is gitignored. Point `MICRO_GRID_DB` somewhere else to move it.

---

## Configuration

`application.properties` holds the shared defaults; `application-dev.properties` and
`application-prod.properties` hold what differs. Every production setting reads an environment
variable first, so a deployment can be retargeted or its secrets rotated without a rebuild.

| Property | Env var | Default | What it does |
|---|---|---|---|
| `spring.profiles.active` | `SPRING_PROFILES_ACTIVE` | `dev` | `dev` or `prod` |
| `micro-grid.cors.allowed-origins` | `MICRO_GRID_ALLOWED_ORIGINS` | `http://localhost:4200` (dev) | Origins allowed on `/api/**`. Serve the frontend on another port and this has to follow it. |
| `spring.datasource.url` | `MICRO_GRID_DB` | `microgrid.db` | The SQLite file. In a container point it at the declared `/home` volume. |
| `council.auth.username` | `COUNCIL_USERNAME` | `council` | |
| `council.auth.password` | `COUNCIL_PASSWORD` | `Wollongong2026!` | Override this in any real deployment. |
| `council.auth.token` | `COUNCIL_TOKEN` | `council-demo-token` | The bearer token login hands out. |
| `nrel.api.key` | `NREL_API_KEY` | *(blank)* | Free PVWatts key. Blank disables the PVWatts half of `/api/solar`. |
| `micro-grid.recaptcha.enabled` | `RECAPTCHA_ENABLED` | `false` | See below. |
| `micro-grid.recaptcha.api-key` | `RECAPTCHA_API_KEY` | — | Google Cloud API key for the assessment call. |
| `micro-grid.recaptcha.min-score` | `RECAPTCHA_MIN_SCORE` | `0.5` | Below this a request is refused. |
| `micro-grid.recaptcha.exempt-paths` | `RECAPTCHA_EXEMPT_PATHS` | *(empty)* | Ant patterns under `/api` that skip verification. Empty on purpose. |

The connection pool is capped at **one connection**: SQLite takes a write lock over the whole
file and the loader writes the entire dataset in one transaction at startup. Everything
afterwards is reads.

---

## API

Base URL `http://localhost:8080`. Everything returns JSON.

### Public — the map

| Method | Path | Returns |
|---|---|---|
| `GET` | `/api/grid/region` | Extent, resolution limits, the surveyed sub-area, and the opening camera position — so the client hard-codes none of it |
| `GET` | `/api/grid/cells?south=&west=&north=&east=&zoom=` | The hexagon overlay for one viewport. Cell size halves with every zoom level out, bottoming out at 50 m from zoom 16 in; the viewport is clipped to the region rather than refused |
| `GET` | `/api/grid/sites` | The generators and load clusters drawn as markers |
| `GET` | `/api/grid/installations` | The real rooftop fleet — LGA totals plus each area's apportioned share, read straight from SQLite |
| `GET` | `/api/grid/status?lat=&lng=` | Live supply, demand, surplus, `SURPLUS`/`BALANCED`/`PEAK` state, export-constraint flag, indicative P2P price, and the nearest community battery's charge. 404 outside the region |
| `GET` | `/api/grid/community-batteries` | Endeavour Energy's four Illawarra neighbourhood batteries |
| `GET` | `/api/solar/{postcode}` | Open-Meteo weather and irradiance, a live PV estimate, the PVWatts model and a naive battery projection. PVWatts is skipped when no key is set |

`/api/grid/status` is keyed on coordinates rather than the spec's `areaId`: the client
geocodes whatever the user typed and sends the point, so any address in the region works
without a lookup table of area names.

### Public — the wizard

| Method | Path | Body | Returns |
|---|---|---|---|
| `POST` | `/api/wizard/plan` | `WizardAnswers` | `OutcomePlan` |

`WizardAnswers` carries the location the client geocoded plus the role and the answers to at
most seven questions. `role` is the only required field — every other answer may be `NOT_SURE`
or absent, and the unknowns become "find this out" follow-ups rather than dead ends.

`OutcomePlan` comes back as a headline dollar range, a count of the options that cost nothing
up front, recommendations split into **do this week** / **do this month** (each with its own
annual range, upfront cost, effort, how-to and source), the live `GridStatus`, a tip keyed to
that grid state, general tips, and the follow-ups.

### Council — token required

Sign in first:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"council","password":"Wollongong2026!"}'
```

Then send `Authorization: Bearer <token>` on everything below. Without it,
`CouncilAuthInterceptor` answers `401` with a JSON body.

| Method | Path | Returns |
|---|---|---|
| `GET` | `/api/council/summary` | The KPI strip: installations, PV density, houses in the LGA, installed kW, annual savings, CO₂ offset, average bill, grid cost, and the rebate gap |
| `GET` | `/api/council/suburbs` | Every locality with its solar figures, equity score, hotspot flag, and the methodology |
| `GET` | `/api/council/suburbs/{name}` | One locality, its postcode's consumption, its score breakdown and its postcode peers. 404 if unknown |
| `GET` | `/api/council/hotspots` | Only the flagged localities, worst first |
| `GET` | `/api/council/trend` | Capacity and installations per month, Jan 2001 – Dec 2025, with annotations |
| `GET` | `/api/council/consumption` | MWh by postcode, split domestic / commercial / industrial |
| `GET` | `/api/council/rebates` | Six years of rebate programs and the take-up gap |
| `GET` | `/api/council/equity-map` | Locality, point, score and the layers the map can colour by |
| `GET` | `/api/council/grid-stress` | **Live.** Per suburb: state, export constraint, share of day in peak, supply, demand, price signal, battery charge |
| `GET` | `/api/council/sources` | Every file and model behind the dashboard, for the provenance footer |

Everything except `/grid-stress` is last financial year's spreadsheets and cannot change while
the dashboard is open — the Angular client caches those for the life of the page and refetches
only the live one.

`CouncilApi.java` is the single definition of these wire shapes;
`micro-grid-frontend/src/app/models/council.models.ts` mirrors it field for field, and the two
have to change together.

---

## How it fits together

```
src/main/java/com/mirco_grid/backend/
├── BackendApplication.java
├── config/
│   ├── WebConfig.java                CORS + registers both interceptors
│   ├── CouncilAuthInterceptor.java   the door on /api/council/**
│   ├── RecaptchaConfig.java          builds the reCAPTCHA beans, only when enabled
│   └── RecaptchaInterceptor.java     the door on /api/** in production
├── controller/                       Auth · Council · Grid · Wizard · Test(solar)
└── service/
    ├── GridService.java              the hexagon field
    ├── BuiltUpDensity.java           the lattice that shapes it
    ├── IllawarraRegion.java          extent, surveyed sub-area, camera
    ├── IllawarraWater.java           traced coastline, so no cell lands on the sea
    ├── GridAllocationService.java    real fleet → modelled areas
    ├── EnergyWizardService.java      answers → ranked plan
    ├── WeatherDataService.java       Open-Meteo + NREL PVWatts
    ├── council/                      loaders, repository, equity index, dashboard
    └── recaptcha/                    assessment client, actions, verdict
```

### The grid model

The region is a tall coastal strip — roughly 45 km wide by 115 km of coast, Waterfall down to
Jervis Bay. Tiling it at the finest 50 m resolution would take millions of hexagons, and below
zoom 16 a 50 m hexagon is under a pixel wide. So cells are built only for
the viewport asked for and the resolution halves with every zoom level out, which keeps roughly
a thousand hexagons on screen at any zoom. `MAX_CELLS` (9,000) caps a response and sets a
`truncated` flag rather than silently dropping cells.

Cell values follow `BuiltUpDensity`, a base64 lattice of how built-up each point is.
Generation and headroom rise faster than load through the suburbs, so the overlay greens over
the towns and reddens over the escarpment, the national parks and the farmland.
`IllawarraWater` carries a traced, Douglas-Peucker-simplified coastline so nothing is drawn on
the sea.

The **modelled overlay covers less than the map does** — Helensburgh to Kiama, against a camera
that covers the whole region. `/api/grid/region` sends both extents and a note, so the client
can say which is which instead of leaving the difference looking like a loading failure.

### Real fleet onto modelled areas

The two "Total System" exports give the LGA's real totals — 27,773 systems, 189,737 kW, Jan
2001 to Dec 2025 — as a single monthly figure for the whole LGA with nothing about location.
The grid model knows where supply and demand sit but nothing about how many systems are behind
them. `GridAllocationService` joins the two by apportioning the real totals across the map's
areas in proportion to what the model already says about each: **households and power stations
follow supply, commercial follows demand**, because a rooftop system is generation and a
commercial system sits on a business.

Nothing is written back to the grid model — the cells are read as weights and none is altered.
The result is written to SQLite at startup, so no request touches a CSV.

### The council data pipeline

`EnergyDataLoader` runs at startup against the files in `src/main/resources/data/`, kept under
their original names so a refreshed drop from Council needs no renaming.

| Reader | File | Tables |
|---|---|---|
| `SuburbSolarCsvReader` | both "Installations+Capacity by suburb" CSVs | `lga_summary`, `suburb_solar`, `suburb_solar_current` |
| `MonthlySeriesCsvReader` | both "Total System" CSVs | `monthly_capacity`, `monthly_installations` |
| `ConsumptionWorkbookReader` | `Wollongong LGA electricity consumption 21-22.xlsx` | `postcode_consumption` |
| `RebateWorkbookReader` | `NSW_Energy_Social_Programs_..._Trends_Analysis.xlsx` | `rebate_trend` |
| `SuburbSeed` | `illawarra/suburb-seed.csv` | `suburb_postcode` |

Straight JDBC, not JPA: the challenge ships spreadsheets rather than an API, so every table's
shape is dictated by the file it comes from and there is no domain model to map.

These files have a house style, and `DataValues` is where it is undone in one place — numbers
quoted with thousands separators (`"27,776"`), a hyphen for a missing value, percentages
carrying their sign, headline figures prefixed with `~` or `$` and sometimes suffixed with a
unit, and a few localities carrying a footnote marker (`"Avon *"`). The readers keep their own
quirks: the suburb CSVs open with a fifteen-line LGA summary, a blank line and three stacked
header rows, and key off content rather than line numbers so a re-export with one more headline
figure still loads; the two monthly series write their dates differently (`Jan 2001` vs
`Jan-01`) and one leaves a trailing empty column and an unlabelled totals row; the rebate
workbook welds footnote digits onto its program and metric names (`"NSW Gas Rebate5"`).

Two figures the dashboard quotes are **not** in the loaded files — average annualised bill
$1,521/yr and average grid cost 35.8 c/kWh. They live in `CouncilConstants` with the table they
came from, rather than shipping a nine-megabyte workbook to extract two numbers, and each is
sent to the browser with that citation attached.

### The equity index

`EquityIndexService` scores every locality 0–100 on how well rooftop solar serves it:

```
equity = 100 × (0.5 · norm(solar_density) + 0.2 · norm(growth) + 0.3 · (1 − norm(consumption)))
```

Each term is min-maxed across the LGA first, so a score answers "compared with the rest of
Wollongong" rather than being absolute. The bottom quartile is flagged as a hotspot, and a
locality needs a minimum installation count before its own growth rate is trusted.

Solar density needs a dwelling count that the challenge data does not have by suburb — it has
installations by suburb and electricity accounts by postcode. Splitting a postcode's accounts
across its suburbs was tried and discarded: with no suburb boundaries to weight by it produced
densities over 200%. So density is computed at the level the data supports — residential
installations in a postcode over that postcode's domestic accounts — and every suburb in that
postcode carries it, separated from its neighbours by its own growth rate. **Nothing is
estimated.** That resolution is the finding, not a limitation to hide, and the methodology
travels with every response so the dashboard states it on screen.

The index is computed once on first use and held, never stored: every input is fixed once the
files are loaded, so changing a weight is a code change and a restart, not a migration.

---

## Security

Two interceptors, registered in `WebConfig`.

**`CouncilAuthInterceptor`** guards `/api/council/**`. One role, one fixed token, and an
interceptor rather than a Spring Security filter chain — putting Security in front of an app
whose other endpoints are deliberately public would cost more configuration than it buys.
Swapping in a real identity provider is a change to `CouncilAuth` and this one class.

**`RecaptchaInterceptor`** guards `/api/**` — every URL, not a chosen few, because the public
endpoints are the expensive ones. It reads a token from `X-Recaptcha-Token`, scores it against
reCAPTCHA Enterprise, and refuses with `403` (not `401` — there is no credential the caller
could supply to fix it).

`CreateAssessment` talks to the REST endpoint directly rather than through
`google-cloud-recaptchaenterprise`: the client library authenticates only with Application
Default Credentials, which a container on a VPS does not have, while an API key in a header
needs nothing but an environment variable. Dropping the library also took roughly 70 MB of gRPC
and protobuf out of the jar. The key goes in `X-goog-api-key` rather than a `?key=` query
parameter, so it never lands in an access log.

The whole reCAPTCHA stack is **conditional on `micro-grid.recaptcha.enabled`** and declared as
`@Bean`s on a `@Configuration` rather than component-scanned. That is deliberate:
`@WebMvcTest` sweeps up every `HandlerInterceptor` bean but not the collaborators one needs, so
a scanned interceptor would break every web slice in the project. `WebConfig` asks for it
through an `ObjectProvider`, which is simply empty when it is off.

`RecaptchaActions` derives one coarse action per API area (plus `LOGIN`) rather than one per
URL — actions are what the reCAPTCHA console groups scores by, and per-URL actions with
locality names in them would give thousands of single-request buckets and no signal.
`recaptchaActionFor` in the frontend's `core/api.ts` derives the same string from the same
path; **the two have to change together.**

It is currently **off in both dev and prod** — the deployment has no Google Cloud API key, and
with the guard on and no key every `/api` URL is refused. `application-prod.properties`
documents exactly what to set to turn it back on, and it has to happen in the same deployment
that rebuilds the frontend with `recaptcha.enabled: true`.

---

## Tests

```bash
./mvnw test
```

| Suite | Covers |
|---|---|
| `GridServiceTest`, `GridControllerTest` | Cell sizing, viewport clipping, the `MAX_CELLS` cap, region bounds |
| `GridAllocationServiceTest`, `GridInstallationsTest` | The apportionment, and that totals survive it |
| `EnergyWizardServiceTest` | Eligibility rules and ranking across all three roles |
| `EquityIndexServiceTest` | Weights, normalisation, hotspot quartile, unranked localities |
| `EnergyDataReadersTest`, `DataValuesTest` | Every parsing quirk in the source files |
| `CouncilAccessTest`, `CouncilAuthTest` | The token door, including CORS preflight |
| `RecaptchaInterceptorTest`, `CreateAssessmentTest`, `RecaptchaActionsTest` | The guard, driven against a mocked assessment client |
| `WeatherDataServiceTest` | Open-Meteo and PVWatts handling, including the no-key path |

The surefire configuration in `pom.xml` forces `micro-grid.recaptcha.enabled=false` as a
**system property**, which outranks both `application-prod.properties` and the environment in
Spring's precedence order. Without it, `mvn package` would fail on any machine without a Google
service account — including a production build, which is the whole point of one.

---

## Docker

```bash
./mvnw package
docker build -t microgrid-backend .
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e MICRO_GRID_DB=/home/microgrid.db \
  -e COUNCIL_PASSWORD='...' \
  -v microgrid-data:/home \
  microgrid-backend
```

The image is `amazoncorretto:21-alpine3.19` with the fat jar and a `/home` volume. The SQLite
file is rebuilt from the packaged spreadsheets whenever it is absent, so pointing
`MICRO_GRID_DB` at that volume is what keeps it across restarts. It is deliberately not
defaulted to `/home/microgrid.db` in the properties: an absolute POSIX path is not resolvable
on a Windows machine running the `prod` profile.

Under `prod`, `server.error.include-stacktrace` and `include-message` are both `never`.
