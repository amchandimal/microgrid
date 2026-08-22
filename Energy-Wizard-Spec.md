# Energy Outcome Wizard — Spec & Proposed Solutions

Goal: a short wizard that asks each user a few questions, calls the **area Grid Supply/Demand API**, and outputs a ranked "Maximum Outcome Plan" — the set of actions that saves/earns them the most money. Designed so renters, apartment dwellers and people without roof access get first-class outcomes, not leftovers.

---

## 1. Wizard Flow

### Step 0 — Location
> "Where do you live? (suburb or postcode)"

Drives: which Grid Supply/Demand API to call, whether a community battery exists nearby (Endeavour Energy has 13+ live in the Illawarra: Dapto, Warrawong, Russell Vale, Shell Cove), and which suburb equity data to show.

### Step 1 — Role
> "Which best describes you?"
- 🏠 **I own the home I live in** (HomeOwner)
- 🔑 **I own a property others live in** (Landlord)
- 🛋️ **I rent my home** (Renter)

### Step 2 — Role questions

**Renter**
| # | Question | Why |
|---|----------|-----|
| R1 | What type of home? (House / Apartment or unit / Townhouse) | Apartment → shared-solar grant path |
| R2 | Do you have a smart meter? (Yes / No / Not sure — "check your meter box: digital display = likely yes") | Gates Solar Sharer + P2P |
| R3 | Is anyone usually home between 11am–2pm on weekdays? (Often / Sometimes / Rarely) | Solar Sharer value; timer advice |
| R4 | Do you hold a concession card (Pensioner, Health Care, Veteran)? *"This unlocks extra rebates"* | Gates subsidised solar-garden plots + NSW rebates |
| R5 | Would you prefer options with no upfront cost, or are you open to a one-off investment that pays back over time? | $0 options vs solar-garden plot |
| R6 | Do you run an EV, pool pump, or other big appliances? | Load-shifting value |

**HomeOwner**
| # | Question | Why |
|---|----------|-----|
| H1 | Home type? (House / Apartment I own) | Apartment owner → no-roof branch (gets Renter options + strata path) |
| H2 | Do you have rooftop solar? (Yes + approx kW / No) | Seller vs buyer path |
| H3 | Do you have a home battery? | VPP / peak-discharge advice |
| H4 | Smart meter? | Gates P2P + Solar Sharer |
| H5 | Is anyone home during the day? | Self-consumption vs sell |
| H6 | Would you like to sell your surplus to neighbours at a price you set? | Marketplace onboarding |
| H7 | Concession card? *(same phrasing as R4)* | Rebates |

**Landlord**
| # | Question | Why |
|---|----------|-----|
| L1 | Property type? (Single dwelling / Multi-unit or apartment block) | Solar Banks 50% rebate is multi-unit |
| L2 | Solar installed on the property? | Split-billing vs install path |
| L3 | Interested in solar if rebates covered a large share of the cost? | Solar for Apartment Residents co-fund |
| L4 | Would you offer tenants solar power as part of the tenancy (split billing / rent inclusion)? | Tenant-retention pitch |

### Inclusive phrasing rules (build these into copy review)
- **Never ask about income.** Ask about concession cards, and frame it as *unlocking* extra benefits.
- Never say "can't afford" — say *"prefer no upfront cost."*
- Renter landing line: *"No roof? No problem — most of these options don't need one."*
- All savings shown as positive outcomes ("save up to $X"), never as hardship framing.
- "Not sure" is always an answer option; the wizard still produces a plan and tells them how to find out.

---

## 2. Grid Supply/Demand API (per area)

Each area has its own endpoint. Wizard calls it at result time and on the plan page (poll ~5 min).

```
GET /api/grid/{areaId}/status
```
```json
{
  "areaId": "wollongong-north",
  "timestamp": "2026-08-22T13:05:00+10:00",
  "supplyKw": 4200,
  "demandKw": 2900,
  "surplusKw": 1300,
  "state": "SURPLUS",          // SURPLUS | BALANCED | PEAK
  "exportConstrained": false,   // local network export limit hit
  "priceSignalCkwh": 8.5,       // indicative local P2P price
  "communityBatterySocPct": 71
}
```

### How grid state changes the advice

| Grid state | Renter / buyer advice | HomeOwner with solar | Battery owner |
|---|---|---|---|
| **SURPLUS** (supply > demand) | "Cheap/free power now — run washing, charge devices, pre-cool/heat. Solar Sharer window likely active." Buy P2P at low price. | Self-consume or charge battery; P2P sell price low — hold if possible | Charge now |
| **BALANCED** | Normal usage | Sell at standard spread (~15c) | Hold |
| **PEAK** (demand > supply) | "Defer big appliances 1–2 hrs if you can — prices highest now" | Sell surplus at premium on marketplace | Discharge/sell — highest earnings |
| **exportConstrained = true** | (no change) | "Grid export capped in your area — divert to battery, community battery, or P2P within your area instead of grid export" | Absorb neighbours' surplus |

This is the feasibility hook for judges: export constraints are real (Endeavour DAPR data) and the app routes energy around them.

---

## 3. Proposed Solutions by Wizard Inputs (the recommendation engine)

Each recommendation has: id, eligibility rule, estimated annual benefit, effort, upfront cost. Engine filters by eligibility → sorts by estimated $ benefit → top item becomes the "headline outcome."

### Renter recommendations

| Rank logic | Recommendation | Eligibility rule | Est. benefit / yr | Upfront |
|---|---|---|---|---|
| Always first if eligible | **Solar Sharer Offer** — up to 24 kWh FREE every day, 11am–2pm (NSW). Opt-in through your retailer; no solar or roof needed. | R2 = Yes (smart meter). If No/Not sure → step: "request free smart meter upgrade from retailer" | ~$300–500 (more if R3 = Often, or timers/R6) | $0 |
| Core equity mechanism | **Subscribe to a neighbour's surplus (P2P marketplace)** — buy at ~15c vs ~32c retail; transparent price set in the app | Smart meter + a seller in area (check API areaId has sellers) | ~$200–400 | $0 |
| If battery nearby | **Join your community battery** — store the neighbourhood's midday surplus, use it at night | Community battery within area (Endeavour list) | ~$400 | $0 |
| If R4 = Yes | **Subsidised solar garden plot (Solar Banks)** — own a slice of a solar farm; credit lands on your bill and *moves with you when you move* | Concession card (subsidised) or R5 = open to investing (full price ~$4,200 → ~$505/yr for 10 yrs, Haystacks model) | ~$500–600 | $0–4,200 |
| If R1 = Apartment | **Shared solar for your building** — NSW co-funds rooftop solar on apartment blocks (avg saving >$1,000/yr). One-tap: generate a pre-written note to your landlord/strata | Apartment/unit | ~$1,000 | $0 (grant + landlord) |
| Always | **Rebate check** — NSW Low Income Household Rebate & friends if R4 = Yes | Concession card | ~$285+ | $0 |

### HomeOwner recommendations

| Recommendation | Eligibility rule | Est. benefit / yr |
|---|---|---|
| **Sell surplus P2P at your price** — earn ~15c vs ~5c feed-in tariff (3× your export income) and a neighbour saves too | H2 = Yes, H6 = Yes, smart meter | +$300–600 vs FiT |
| **Time exports with the grid** — app pings you: sell at PEAK, self-consume at SURPLUS; auto if battery | H2 = Yes | +$100–300 |
| **Battery + VPP enrolment** — discharge at peak, get network payments | H3 = Yes | +$200–400 |
| **Export-constraint workaround** — when API says constrained: divert to battery/community battery/local P2P instead of losing curtailed export | exportConstrained area | Avoids ~$100s lost |
| **No solar yet** → NSW rebates + battery incentives quote; meanwhile all Renter $0 options apply (Solar Sharer works without panels) | H2 = No | ~$1,000+ (solar) |
| **Apartment owner (no roof)** → Renter path + raise shared-solar grant at strata AGM (owner has a vote — stronger than tenant ask) | H1 = Apartment | ~$1,000 |

### Landlord recommendations

| Recommendation | Eligibility rule | Benefit |
|---|---|---|
| **Solar Banks rebate — up to 50% off install on multi-unit dwellings** | L1 = Multi-unit | Half-price asset |
| **Solar for Apartment Residents co-fund** — government co-pays shared solar for the building | L1 = Multi-unit, L3 = Yes | Grant-funded install |
| **Solar as a tenancy feature** — split billing via the platform: tenants buy the roof's power at a fair price below retail; landlord earns ongoing return + retention/rent premium | L2 = Yes or post-install | ~5–10% ROI + happier tenants |
| **List property surplus on marketplace** — vacant periods / daytime surplus sold to neighbours | L2 = Yes, smart meter | +$300–600 |

---

## 4. Output: "Your Maximum Outcome Plan" (rendered markdown template)

```markdown
# Your Maximum Outcome Plan — {name or role}, {suburb}

**Headline: you could save/earn ~${total}/year. {n} of your options cost $0 upfront.**

## Do this week
1. {top rec} — ${est}/yr — {one-line how}
2. {rec 2} — ${est}/yr

## Do this month
3. {rec 3} …

## Right now in {suburb}  ⚡ (live from Grid API)
Grid is in {state}: {contextual tip, e.g. "Solar surplus right now — free-window
appliances on! Community battery at {soc}% charged."}

## How to maximise the energy benefits
- Shift what you can into 11am–2pm (Solar Sharer window): dishwasher, washing,
  EV/device charging, pre-cool or pre-heat the house. Use appliance timers if
  you're out — "home during the day" is not required, timed appliances are.
- Buy P2P / use battery power for your 5–9pm evening peak — that's where the
  money is lost.
- Check the app's grid signal before running big loads: green = go, red = defer.
- {role-specific line: seller → "list your surplus before 5pm peak";
  renter → "your solar-garden credit follows you when you move"}
```

---

## 5. Worked demo personas (for the pitch)

1. **Aisha — renter, Warrawong apartment, smart meter, home days, concession card** → Solar Sharer ($450) + community battery ($400 — Warrawong has 5!) + subsidised solar garden plot ($505) + rebate ($285) = **~$1,640/yr, $0 upfront**. One wizard, 90 seconds.
2. **Tom — homeowner, Dapto, 6.6 kW solar, no battery** → P2P selling at 15c (3× his 5c FiT), peak-time sell alerts; his buyer is literally Aisha's neighbour. Both bills drop — that's the equity story.
3. **Priya — landlord, 8-unit block in Fairy Meadow** → Solar Banks 50% rebate + split billing to tenants at 18c → tenants save ~40%, she earns ~8% ROI.

---

## 6. Sources for the pitch deck

- Solar Sharer: live 1 Jul 2026, NSW window 11am–2pm, up to 24 kWh/day free, smart meter required, renters eligible — [SolarQuotes explainer](https://www.solarquotes.com.au/blog/solar-sharer-3-hours-of-free-power-explained/), [EnergyAustralia offer](https://www.energyaustralia.com.au/sso), [pv-tech](https://www.pv-tech.org/australia-mandates-three-hour-free-solar-electricity-for-households-from-2026/)
- Solar Banks: 50% multi-unit rebate; subsidised solar-garden plots, ~$600/yr — [NSW Gov](https://www.nsw.gov.au/departments-and-agencies/homes-nsw/news/cost-of-living-energy-upgrades-for-households)
- Solar for Apartment Residents: 199 projects, 3,444 households, avg >$1,000/yr saved — [NSW Energy](https://www.energy.nsw.gov.au/households/grants-rebates/solar-for-apartment-residents)
- Community batteries Illawarra: 13 live / 22 planned, ~$400/yr incl. renters — [Endeavour Energy](https://www.endeavourenergy.com.au/about-us/newsroom/second-community-battery-for-nsw-south), [energy.gov.au](https://www.energy.gov.au/news/switching-community-batteries-illawarra-region)
- Solar garden model (plot → bill credit that moves with you): [Haystacks](https://haystacks.solargarden.org.au/about/)
- P2P legality (settled via authorised retailer, live today): [Localvolts](https://localvolts.com/how-it-all-works/)
- Export constraints: [Endeavour DAPR](https://dapr.endeavourenergy.com.au/connections/)
