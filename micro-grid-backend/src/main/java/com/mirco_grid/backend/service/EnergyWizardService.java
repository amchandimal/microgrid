package com.mirco_grid.backend.service;

import com.mirco_grid.backend.service.OutcomePlan.Effort;
import com.mirco_grid.backend.service.OutcomePlan.Recommendation;
import com.mirco_grid.backend.service.OutcomePlan.Timing;
import com.mirco_grid.backend.service.WizardAnswers.Answer;
import com.mirco_grid.backend.service.WizardAnswers.Presence;
import com.mirco_grid.backend.service.WizardAnswers.Role;
import com.mirco_grid.backend.service.WizardAnswers.Upfront;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Turns wizard answers into a ranked plan.
 *
 * <p>Eligibility filters first, then sort by what it is worth, then the top
 * item becomes the headline. Options that need no roof and no money up front
 * are the point of the exercise, not an afterthought - a renter in a flat
 * should come out of this with a real number, so those recommendations are
 * scored on the same footing as anything a homeowner gets.
 */
@Service
public class EnergyWizardService {

    private final GridService gridService;

    public EnergyWizardService(GridService gridService) {
        this.gridService = gridService;
    }

    public Optional<OutcomePlan> plan(WizardAnswers answers, ZonedDateTime when) {
        Optional<GridStatus> status = gridService.statusAt(answers.lat(), answers.lng(), when);
        if (status.isEmpty()) {
            return Optional.empty();
        }
        GridStatus grid = status.get();

        List<Recommendation> eligible = new ArrayList<>();
        switch (answers.role()) {
            case RENTER -> eligible.addAll(renterOptions(answers, grid));
            case HOMEOWNER -> eligible.addAll(homeOwnerOptions(answers, grid));
            case LANDLORD -> eligible.addAll(landlordOptions(answers, grid));
        }

        // Worth the most first; the top of the list is the headline outcome.
        eligible.sort(Comparator
                .comparingInt((Recommendation r) -> -(r.annualLow() + r.annualHigh()))
                .thenComparing(Recommendation::upfrontCost));

        List<Recommendation> week = eligible.stream()
                .filter(r -> r.timing() == Timing.THIS_WEEK).toList();
        List<Recommendation> month = eligible.stream()
                .filter(r -> r.timing() == Timing.THIS_MONTH).toList();

        return Optional.of(new OutcomePlan(
                answers.suburb(),
                answers.role(),
                eligible.stream().mapToInt(Recommendation::annualLow).sum(),
                eligible.stream().mapToInt(Recommendation::annualHigh).sum(),
                (int) eligible.stream().filter(Recommendation::isFree).count(),
                week,
                month,
                grid,
                gridTip(answers, grid),
                maximiseTips(answers),
                findOutNext(answers)));
    }

    // --- renter ---------------------------------------------------------------

    private List<Recommendation> renterOptions(WizardAnswers a, GridStatus grid) {
        List<Recommendation> out = new ArrayList<>();

        // Solar Sharer is the first thing a renter should have, when the meter allows.
        if (a.hasSmartMeter()) {
            int bump = (a.daytimePresence() == Presence.OFTEN ? 120 : 0)
                    + (a.bigAppliances() ? 80 : 0);
            out.add(new Recommendation(
                    "solar-sharer",
                    "Switch on the Solar Sharer window",
                    "Up to 24 kWh free every day between 11am and 2pm. No roof, no panels, "
                            + "no upfront cost - you opt in through your retailer.",
                    300 + bump, 500 + bump, 0, Effort.ONE_TAP, Timing.THIS_WEEK,
                    "Ask your retailer to put you on their Solar Sharer offer, then set "
                            + "appliance timers for the window.",
                    "NSW Solar Sharer, live 1 July 2026"));
        } else {
            out.add(new Recommendation(
                    "smart-meter-upgrade",
                    "Ask for a free smart meter upgrade",
                    "A smart meter is the key to the free-power window and to buying from "
                            + "neighbours. Retailers install them at no cost.",
                    300, 500, 0, Effort.SHORT, Timing.THIS_WEEK,
                    "Call your retailer and request a smart meter upgrade, then come back "
                            + "and switch on Solar Sharer.",
                    "NSW Solar Sharer eligibility"));
        }

        if (a.hasSmartMeter()) {
            out.add(new Recommendation(
                    "p2p-buy",
                    "Buy your power from a neighbour",
                    "Around 15c/kWh instead of about 32c retail, at a price you can see "
                            + "before you agree to it.",
                    200, 400, 0, Effort.ONE_TAP, Timing.THIS_WEEK,
                    "Pick a seller in your area on the map and subscribe.",
                    "Peer-to-peer settled through an authorised retailer"));
        }

        if (grid.communityBattery() != null) {
            out.add(new Recommendation(
                    "community-battery",
                    "Join the " + grid.communityBattery().suburb() + " community battery",
                    "The neighbourhood stores its midday surplus and you draw on it at "
                            + "night. Open to renters.",
                    350, 450, 0, Effort.SHORT, Timing.THIS_MONTH,
                    "Register with Endeavour Energy's community battery programme for "
                            + grid.communityBattery().suburb() + ".",
                    "Endeavour Energy - 13 live in the Illawarra"));
        }

        if (a.concessionCard()) {
            out.add(new Recommendation(
                    "solar-garden-subsidised",
                    "Take a subsidised solar garden plot",
                    "Own a slice of a solar farm. The credit lands on your bill and moves "
                            + "with you when you move house.",
                    500, 600, 0, Effort.SHORT, Timing.THIS_MONTH,
                    "Apply through the NSW Solar Banks programme - concession card holders "
                            + "get the subsidised rate.",
                    "NSW Solar Banks / Haystacks model"));
            out.add(new Recommendation(
                    "rebate-check",
                    "Claim the rebates you already qualify for",
                    "The NSW Low Income Household Rebate and its companions are worth "
                            + "hundreds a year and take one form.",
                    285, 350, 0, Effort.ONE_TAP, Timing.THIS_WEEK,
                    "Apply through your retailer with your concession card number.",
                    "NSW Low Income Household Rebate"));
        } else if (a.upfrontPreference() == Upfront.OPEN_TO_INVESTMENT) {
            out.add(new Recommendation(
                    "solar-garden-full",
                    "Buy a solar garden plot outright",
                    "About $4,200 for a plot returning roughly $505 a year for ten years - "
                            + "and the credit follows you if you move.",
                    505, 505, 4200, Effort.INVOLVED, Timing.THIS_MONTH,
                    "Register interest with a solar garden operator.",
                    "Haystacks solar garden"));
        }

        if (a.isApartment()) {
            out.add(new Recommendation(
                    "shared-solar-building",
                    "Get solar onto your building",
                    "NSW co-funds rooftop solar for apartment blocks - buildings that have "
                            + "done it save over $1,000 a year on average.",
                    900, 1100, 0, Effort.ONE_TAP, Timing.THIS_MONTH,
                    "Send the pre-written note to your landlord or strata committee - the "
                            + "grant does the persuading.",
                    "NSW Solar for Apartment Residents"));
        }

        return out;
    }

    // --- home owner -----------------------------------------------------------

    private List<Recommendation> homeOwnerOptions(WizardAnswers a, GridStatus grid) {
        List<Recommendation> out = new ArrayList<>();
        boolean hasSolar = a.hasSolar() == Answer.YES;

        if (hasSolar && a.wantsToSellSurplus() && a.hasSmartMeter()) {
            out.add(new Recommendation(
                    "p2p-sell",
                    "Sell your surplus to neighbours at your price",
                    "About 15c/kWh against a 5c feed-in tariff - roughly three times the "
                            + "export income, and the buyer saves too.",
                    300, 600, 0, Effort.SHORT, Timing.THIS_WEEK,
                    "List your surplus on the marketplace and set your price.",
                    "Peer-to-peer settled through an authorised retailer"));
        }

        if (hasSolar) {
            out.add(new Recommendation(
                    "time-exports",
                    "Time your exports to the grid signal",
                    "Sell when the neighbourhood is short and self-consume when it is long. "
                            + "The app tells you which it is.",
                    100, 300, 0, Effort.ONE_TAP, Timing.THIS_WEEK,
                    "Turn on grid-state alerts and watch the map before running big loads.",
                    "Local supply and demand, this page"));
        }

        if (a.hasBattery() == Answer.YES) {
            out.add(new Recommendation(
                    "battery-vpp",
                    "Enrol your battery in a virtual power plant",
                    "Discharge into the evening peak and collect network payments for it.",
                    200, 400, 0, Effort.SHORT, Timing.THIS_MONTH,
                    "Ask your battery installer or retailer which VPP they support.",
                    "VPP network payments"));
        }

        if (grid.exportConstrained() && hasSolar) {
            out.add(new Recommendation(
                    "export-workaround",
                    "Route around the export cap",
                    "Your area's network is at its export limit, so grid export gets "
                            + "curtailed. Send it to a battery or a neighbour instead of losing it.",
                    150, 400, 0, Effort.SHORT, Timing.THIS_WEEK,
                    "Divert to your own battery, the community battery, or a local buyer.",
                    "Endeavour DAPR export constraints"));
        }

        if (!hasSolar && !a.isApartment()) {
            out.add(new Recommendation(
                    "install-solar",
                    "Get a quote for rooftop solar with the rebates applied",
                    "NSW rebates and battery incentives take a large bite out of the "
                            + "install cost, and the system pays for itself from day one.",
                    900, 1400, 0, Effort.INVOLVED, Timing.THIS_MONTH,
                    "Request quotes with the current NSW rebates included.",
                    "NSW household energy upgrades"));
        }

        // No roof of their own - the renter options are the real answer, plus a
        // vote at the strata meeting a tenant does not get.
        if (a.isApartment()) {
            out.addAll(renterOptions(a, grid));
            out.add(new Recommendation(
                    "strata-shared-solar",
                    "Put shared solar on the strata agenda",
                    "As an owner you get a vote, which carries further than a tenant's "
                            + "request. NSW co-funds the install.",
                    900, 1100, 0, Effort.SHORT, Timing.THIS_MONTH,
                    "Raise it at the next AGM with the grant details attached.",
                    "NSW Solar for Apartment Residents"));
        } else if (!hasSolar) {
            // Solar Sharer needs no panels, so it applies here too.
            out.addAll(renterOptions(a, grid).stream()
                    .filter(r -> r.id().equals("solar-sharer")
                            || r.id().equals("smart-meter-upgrade")
                            || r.id().equals("rebate-check"))
                    .toList());
        }

        return dedupe(out);
    }

    // --- landlord -------------------------------------------------------------

    private List<Recommendation> landlordOptions(WizardAnswers a, GridStatus grid) {
        List<Recommendation> out = new ArrayList<>();
        boolean multiUnit = a.isApartment();
        boolean hasSolar = a.hasSolar() == Answer.YES;

        if (multiUnit) {
            out.add(new Recommendation(
                    "solar-banks-rebate",
                    "Claim the Solar Banks multi-unit rebate",
                    "Up to 50% off the install on multi-unit dwellings - a half-price asset "
                            + "on a building you already own.",
                    800, 1200, 0, Effort.INVOLVED, Timing.THIS_MONTH,
                    "Apply through NSW Solar Banks before the install.",
                    "NSW Solar Banks - 50% multi-unit rebate"));
        }

        if (multiUnit && a.openToSubsidisedSolar()) {
            out.add(new Recommendation(
                    "apartment-cofund",
                    "Take the Solar for Apartment Residents co-fund",
                    "Government co-pays shared solar for the building. Residents on the "
                            + "199 completed projects save over $1,000 a year on average.",
                    1000, 1200, 0, Effort.INVOLVED, Timing.THIS_MONTH,
                    "Register the building with the NSW programme.",
                    "NSW Solar for Apartment Residents"));
        }

        if (hasSolar || a.wouldOfferSolarToTenants()) {
            out.add(new Recommendation(
                    "split-billing",
                    "Offer the roof's power as part of the tenancy",
                    "Tenants buy below retail and you earn an ongoing return - around 5 to "
                            + "10% ROI, plus the retention that comes with a cheaper bill.",
                    400, 900, 0, Effort.SHORT, Timing.THIS_MONTH,
                    "Enable split billing on the platform and set the tenant rate.",
                    "Split billing via the platform"));
        }

        if (hasSolar && a.hasSmartMeter()) {
            out.add(new Recommendation(
                    "landlord-p2p",
                    "Sell the property's daytime surplus",
                    "Vacant periods and daytime surplus go to neighbours instead of back to "
                            + "the grid at the feed-in rate.",
                    300, 600, 0, Effort.SHORT, Timing.THIS_WEEK,
                    "List the property's surplus on the marketplace.",
                    "Peer-to-peer marketplace"));
        }

        return out;
    }

    // --- context --------------------------------------------------------------

    /** What the grid state means for this particular person, right now. */
    private String gridTip(WizardAnswers a, GridStatus grid) {
        boolean seller = a.role() != Role.RENTER && a.hasSolar() == Answer.YES;
        boolean battery = a.hasBattery() == Answer.YES;
        String soc = grid.communityBatterySocPct() == null ? ""
                : " The " + grid.communityBattery().suburb() + " community battery is at "
                        + grid.communityBatterySocPct() + "%.";

        String tip = switch (grid.state()) {
            case SURPLUS -> seller
                    ? "Your neighbourhood is long on power, so the local price is low. "
                            + "Self-consume or charge rather than selling into it."
                    : "Cheap power right now - this is the moment for the washing, the "
                            + "dishwasher, charging, and pre-cooling or pre-heating.";
            case BALANCED -> seller
                    ? "Steady. Selling at the usual spread of around 15c."
                    : "Steady. Nothing special to do - normal usage.";
            case PEAK -> seller
                    ? "Demand is ahead of supply, so this is when your surplus is worth "
                            + "the most. Sell now."
                    : "Demand is ahead of supply and this is the dearest power of the day. "
                            + "Push any big appliance back an hour or two if you can.";
        };
        if (battery) {
            tip += grid.state() == GridStatus.State.PEAK
                    ? " Discharge your battery - this is the top of the market."
                    : " Charge your battery while power is cheap.";
        }
        if (grid.exportConstrained()) {
            tip += " Export is capped in your area, so send surplus to a battery or a "
                    + "neighbour rather than the grid.";
        }
        return tip + soc;
    }

    private List<String> maximiseTips(WizardAnswers a) {
        List<String> tips = new ArrayList<>();
        tips.add("Move what you can into 11am-2pm: dishwasher, washing, charging, "
                + "pre-cooling or pre-heating. Timers count - you do not have to be home.");
        tips.add("Cover your 5-9pm peak with stored or peer-bought power. That window is "
                + "where the money leaks out.");
        tips.add("Check the grid signal before a big load: green means go, red means wait.");
        if (a.role() == Role.RENTER) {
            tips.add("A solar garden credit is yours, not the building's - it follows you "
                    + "to your next place.");
        } else if (a.hasSolar() == Answer.YES) {
            tips.add("List your surplus before the 5pm peak, when buyers are paying most.");
        } else if (a.role() == Role.LANDLORD) {
            tips.add("A cheaper power bill is a retention feature - say so in the listing.");
        }
        return tips;
    }

    /** Anything answered "not sure" becomes a concrete next step, not a dead end. */
    private List<String> findOutNext(WizardAnswers a) {
        List<String> next = new ArrayList<>();
        if (a.smartMeter() == Answer.NOT_SURE) {
            next.add("Check your meter box - a digital display usually means you already "
                    + "have a smart meter. If not, your retailer will fit one free.");
        }
        if (a.hasSolar() == Answer.NOT_SURE) {
            next.add("Look for an inverter box near your switchboard, or check a recent "
                    + "bill for a feed-in credit line.");
        }
        if (a.hasBattery() == Answer.NOT_SURE) {
            next.add("A home battery is a cabinet near the inverter. Your installer's "
                    + "paperwork will say.");
        }
        return next;
    }

    /** Merging the no-roof branches can offer the same thing twice. */
    private static List<Recommendation> dedupe(List<Recommendation> in) {
        List<Recommendation> out = new ArrayList<>();
        for (Recommendation r : in) {
            if (out.stream().noneMatch(existing -> existing.id().equals(r.id()))) {
                out.add(r);
            }
        }
        return out;
    }
}
