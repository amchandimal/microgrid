# Council Dashboard — Implementation Instructions

Add a **Council login** (fixed credentials) to the micro-grid app and a Council-only dashboard fed by the Energy Equity Challenge data files via **SQLite** in the Spring Boot backend.

Repo: `micro-grid/micro-grid-backend` (Spring Boot, Maven) + `micro-grid/micro-grid-frontend` (Angular 22, Leaflet).
Data files: copy the CSV/XLSX files from `Challenges/Energy Equity Challenge/` into `micro-grid-backend/src/main/resources/data/` (keep filenames exactly as below).

---

## 1. Login with fixed credentials

Hackathon-grade auth (no Spring Security needed — say "demo auth" if judges ask).

**Backend**
- `POST /api/auth/login` with `{ "username", "password" }`.
- Check against constants: `council / Wollongong2026!` → respond `{ "token": "council-demo-token", "role": "COUNCIL", "displayName": "Wollongong City Council" }`. Wrong creds → 401.
- Simple filter (or `HandlerInterceptor`) on `/api/council/**`: require header `Authorization: Bearer council-demo-token`, else 401.

**Frontend (Angular)**
- On the existing landing/role selection, add tile: **"Council"** → `/login/council` (username + password form). Keep HomeOwner/Landlord/Renter tiles going to the wizard.
- `AuthService`: calls login, stores token + role in memory (localStorage optional), exposes `isCouncil()`.
- `councilGuard` (CanActivate) protecting route `/council` → `CouncilDashboardComponent`.
- HTTP interceptor attaches the Bearer token to `/api/council/*` calls.

---

## 2. SQLite in Spring Boot

`pom.xml`:
```xml
<dependency>
  <groupId>org.xerial</groupId>
  <artifactId>sqlite-jdbc</artifactId>
  <version>3.46.0.0</version>
</dependency>
<dependency>
  <groupId>org.hibernate.orm</groupId>
  <artifactId>hibernate-community-dialects</artifactId>
</dependency>
<dependency>
  <groupId>org.apache.poi</groupId>
  <artifactId>poi-ooxml</artifactId>
  <version>5.2.5</version>
</dependency>
<dependency>
  <groupId>com.opencsv</groupId>
  <artifactId>opencsv</artifactId>
  <version>5.9</version>
</dependency>
```

`application.properties`:
```properties
spring.datasource.url=jdbc:sqlite:microgrid.db
spring.datasource.driver-class-name=org.sqlite.JDBC
spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect
spring.jpa.hibernate.ddl-auto=update
```

Ingestion: a `@Component CommandLineRunner DataLoader` that parses the files below on startup **only if tables are empty** (idempotent).

---

## 3. Data files → tables (exact parsing quirks)

All numbers arrive as quoted strings with thousands separators (`"27,776"`), `-` means null, and some suburbs have a footnote marker (`"Avon *"` → strip trailing ` *`).

### 3.1 `Installations+Capacity by Suburb - FY 24-25.csv` → `suburb_solar`
- Lines 1–15: LGA summary key/value pairs → load into `lga_summary(key TEXT PRIMARY KEY, value TEXT)`. Includes: Total installations 27,776; Number of houses in LGA 73,390; LGA residential PV density ~36.4%; Annual total savings ~$30,577,000; CO2 offsets; Avg 1,380 kWh generated per kW installed.
- Then a blank line + **3 header rows** (group / period / column). Data starts after the row beginning `"Locality"`.
- Data columns (13): `Locality, res_installs_alltime, res_kw_alltime, res_installs_fy, res_kw_fy, res_yoy_pct, com_installs_alltime, com_kw_alltime, com_installs_fy, com_kw_fy, com_yoy_pct, ps_installs_fy, ps_kw_fy`.

```sql
CREATE TABLE suburb_solar (
  locality TEXT PRIMARY KEY,
  res_installs_alltime INTEGER, res_kw_alltime REAL,
  res_installs_fy INTEGER, res_kw_fy REAL, res_yoy_pct REAL,
  com_installs_alltime INTEGER, com_kw_alltime REAL,
  com_installs_fy INTEGER, com_kw_fy REAL, com_yoy_pct REAL,
  ps_installs_fy INTEGER, ps_kw_fy REAL
);
```

### 3.2 `Installations+Capacity by suburb - All Time.csv`
Same preamble; data columns (7): `Locality, res_installs, res_kw, com_installs, com_kw, ps_installs, ps_kw`. The FY file already contains all-time columns, so you can **skip this file** or use it to cross-check.

### 3.3 `Total System Capacity - Wollongong - All Time.csv` → `monthly_capacity`
Header `Date,Residential,Commercial,Power Stations`; dates like `Jan 2001` … `Dec 2025` (monthly kW added). Parse with `MMM yyyy`.

### 3.4 `Total System Installations - Wollongong - All Time.csv` → `monthly_installations`
Same shape but dates like `Jan-01` (`MMM-yy`) and a **trailing empty column** — ignore it.

```sql
CREATE TABLE monthly_capacity (month TEXT, residential REAL, commercial REAL, power_stations REAL);
CREATE TABLE monthly_installations (month TEXT, residential INTEGER, commercial INTEGER, power_stations INTEGER);
```

### 3.5 `Wollongong LGA electricity consumption 21-22.xlsx` → `postcode_consumption` (Apache POI)
Sheet1, 2 header rows. Columns: `Postcode, Total MWh, Domestic & Controlled Load, Domestic, Controlled Load, Commercial, Industrial`. Rows are postcodes 2500, 2502, 2505, 2506, …

```sql
CREATE TABLE postcode_consumption (
  postcode TEXT PRIMARY KEY, total_mwh REAL, domestic_mwh REAL,
  controlled_load_mwh REAL, commercial_mwh REAL, industrial_mwh REAL
);
```

### 3.6 NSW Energy Social Programs workbooks → `rebate_trend` (hardship context)
`NSW_Energy_Social_Programs_Annual_Report_2022_2023_Trends_Analysis.xlsx`, sheet **"Table 1"**: parse rebate program blocks (Low Income Household Rebate, NSW Gas Rebate, …) → rows `(program, metric, fy, value)` for FY2017-18 → FY2022-23. Key metrics: `Total customer accounts`, `Total paid amount ($)`, `Estimated number of eligible customers` (accounts vs eligible = the **rebate take-up gap** — a headline equity stat: ~940k receiving vs ~1.25M eligible statewide).
- The big Data Workbook is optional; if time allows, grab EAPA voucher counts (Table 15) the same way.
- Also hardcode two KPI constants with a source note (from Table 24): average annualised electricity bill **$1,521/yr**, average grid cost **35.8 c/kWh**.

```sql
CREATE TABLE rebate_trend (program TEXT, metric TEXT, fy TEXT, value REAL);
```

### 3.7 Suburb → postcode mapping (seed table, needed for joins)
The solar data is by suburb; consumption is by postcode. Seed `suburb_postcode`:

```
2500: Wollongong, Mangerton, Mount Keira, Mount Saint Thomas, West Wollongong, Coniston, Gwynneville, Keiraville, North Wollongong, Spring Hill
2502: Warrawong, Cringila, Lake Heights, Primbee
2505: Port Kembla | 2506: Berkeley | 2508: Helensburgh, Otford, Stanwell Park, Stanwell Tops, Coalcliff
2515: Thirroul, Austinmer, Coledale, Wombarra, Scarborough, Clifton
2516: Bulli | 2517: Woonona, Russell Vale
2518: Corrimal, Bellambi, East Corrimal, Tarrawanna, Towradgi
2519: Balgownie, Fairy Meadow, Mount Ousley, Mount Pleasant, Fernhill
2525: Figtree | 2526: Cordeaux Heights, Farmborough Heights, Unanderra, Kembla Grange, Mount Kembla
2530: Dapto, Horsley, Kanahooka, Koonawarra, Brownsville, Avondale, Cleveland, Penrose, Haywards Bay, Yallah, Marshall Mount, Avon, Huntley, Wongawilli
```

---

## 4. Equity Index (computed, not stored)

Per suburb, 0–100 (higher = better served). Compute in a service:

```
solar_density   = res_installs_alltime / dwellings (fallback: normalize res_installs across suburbs)
growth          = res_yoy_pct (normalized)
consumption_pp  = domestic_mwh of suburb's postcode (normalized, inverted — high use + low solar = stress)
equity_score    = 100 * (0.5*norm(solar_density) + 0.2*norm(growth) + 0.3*(1 - norm(consumption_pp)))
flag: "hotspot" = bottom quartile equity_score
```

Be transparent in the UI about the formula (judges: feasibility). Optional upgrade: add a `suburb_census(locality, renter_pct, median_income)` seed table from profile.id.com.au/wollongong and add `0.3*(1-norm(renter_pct))` weighting — this makes it a true *equity* index, not just a solar index. Rebalance the weights so they still sum to 1 (e.g. 0.35 solar / 0.15 growth / 0.2 consumption / 0.3 tenure).

---

## 5. REST endpoints (all under `/api/council`, token-protected)

| Endpoint | Returns | Source |
|---|---|---|
| `GET /summary` | KPI cards: total installs, PV density 36.4%, houses 73,390, $30.5M annual savings, 154k t CO2, avg bill $1,521, 35.8c/kWh | `lga_summary` + constants |
| `GET /suburbs` | per-suburb solar table + equity_score + hotspot flag (sortable league table + map choropleth) | `suburb_solar` + index calc |
| `GET /suburbs/{name}` | one suburb detail incl. postcode consumption | join via `suburb_postcode` |
| `GET /trend` | monthly capacity + installations 2001→2025 (line chart) | `monthly_capacity/installations` |
| `GET /consumption` | MWh by postcode split domestic/commercial/industrial (stacked bar) | `postcode_consumption` |
| `GET /rebates` | rebate program trends + take-up gap | `rebate_trend` |
| `GET /equity-map` | GeoJSON-ready: locality → equity_score, res_kw, hotspot | for the existing Leaflet hex layer |
| `GET /grid-stress` | proxy your per-area Grid Supply/Demand APIs → % time in PEAK, exportConstrained areas | existing grid APIs |

---

## 6. Council dashboard UI (`/council`)

Reuse the existing map + dashboard-panel components; add a `council-dashboard` component with:

1. **KPI strip** — from `/summary` (installs, PV density, savings, CO2, avg bill).
2. **Equity map** — your Leaflet hex/suburb layer coloured by `equity_score` (red = hotspot, green = well served), toggle layers: solar kW · YoY growth · consumption · live grid stress. Click suburb → detail drawer.
3. **Hotspot list** — "Suburbs needing intervention": bottom-quartile suburbs with a suggested action ("Target Solar Banks subsidies", "Community battery candidate — export constrained + low solar").
4. **Growth trend chart** — capacity & installs 2001–2025; annotate the 2025 dip visible in the data (Oct–Dec 2025).
5. **Consumption chart** — stacked by postcode; note 2500's industrial load dominates (288 GWh industrial vs 80 GWh domestic).
6. **Rebate take-up panel** — accounts vs eligible (the gap = residents missing out) + EAPA trend.
7. **Live grid panel** — areas currently in PEAK / export-constrained from your per-area APIs.

Talking point built into the UI: *"Austinmer has 452 solar installs; Warrawong's postcode uses 30 GWh domestic but sits in the bottom equity quartile — that's where Council targets the next community battery and Solar Banks round."*

---

## 7. Build order (suggested)

1. SQLite deps + `DataLoader` for the two easy CSVs (`suburb_solar`, `monthly_*`) → `/suburbs`, `/trend`
2. Login endpoint + Angular Council tile, guard, dashboard shell with KPI strip + league table
3. Equity index service + map colouring + hotspot list
4. POI parsing (consumption xlsx, rebate Table 1) → charts
5. Grid-stress proxy + polish
