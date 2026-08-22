package com.mirco_grid.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirco_grid.backend.service.OutcomePlan.Recommendation;
import com.mirco_grid.backend.service.WizardAnswers.Answer;
import com.mirco_grid.backend.service.WizardAnswers.HomeType;
import com.mirco_grid.backend.service.WizardAnswers.Presence;
import com.mirco_grid.backend.service.WizardAnswers.Role;
import com.mirco_grid.backend.service.WizardAnswers.Upfront;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EnergyWizardServiceTest {

    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 22);

    private final GridService grid = new GridService(new BuiltUpDensity());
    private final EnergyWizardService wizard = new EnergyWizardService(grid);

    private ZonedDateTime at(double hour) {
        return ZonedDateTime.of(
                DAY, LocalTime.of((int) hour, (int) Math.round((hour % 1) * 60)), SYDNEY);
    }

    // Aisha from the spec: renter, Warrawong apartment, smart meter, home days,
    // concession card.
    private WizardAnswers aisha() {
        return new WizardAnswers("Warrawong NSW 2502", "Warrawong", -34.4881, 150.8869,
                Role.RENTER, HomeType.APARTMENT, Answer.YES, Presence.OFTEN, true,
                Upfront.NO_UPFRONT, false, Answer.NO, null, Answer.NO, false, false, false);
    }

    // Tom: homeowner, Dapto, 6.6 kW solar, no battery, wants to sell.
    private WizardAnswers tom() {
        return new WizardAnswers("Dapto NSW 2530", "Dapto", -34.5030, 150.7930,
                Role.HOMEOWNER, HomeType.HOUSE, Answer.YES, Presence.RARELY, false,
                Upfront.OPEN_TO_INVESTMENT, true, Answer.YES, 6.6, Answer.NO, true, false, false);
    }

    // Priya: landlord, multi-unit block in Fairy Meadow.
    private WizardAnswers priya() {
        return new WizardAnswers("Fairy Meadow NSW 2519", "Fairy Meadow", -34.3944, 150.8983,
                Role.LANDLORD, HomeType.MULTI_UNIT, Answer.YES, Presence.RARELY, false,
                Upfront.OPEN_TO_INVESTMENT, false, Answer.YES, 20.0, Answer.NO, false, true, true);
    }

    private static List<String> ids(OutcomePlan plan) {
        return java.util.stream.Stream
                .concat(plan.doThisWeek().stream(), plan.doThisMonth().stream())
                .map(Recommendation::id)
                .toList();
    }

    // --- the daily shape ------------------------------------------------------

    @Test
    void followsTheSunThroughTheDay() {
        assertThat(GridService.solarFactor(3)).isZero();
        assertThat(GridService.solarFactor(23)).isZero();
        assertThat(GridService.solarFactor(12.5)).isGreaterThan(0.9);
        assertThat(GridService.solarFactor(9)).isLessThan(GridService.solarFactor(12.5));
        assertThat(GridService.solarFactor(16)).isLessThan(GridService.solarFactor(12.5));
    }

    @Test
    void putsTheLoadPeakInTheEvening() {
        double evening = GridService.loadFactor(18.5);
        assertThat(evening).isGreaterThan(GridService.loadFactor(3));
        assertThat(evening).isGreaterThan(GridService.loadFactor(12));
        assertThat(evening).isGreaterThan(GridService.loadFactor(7.5));
    }

    @Test
    void readsSurplusAtMiddayAndPeakAfterDark() {
        GridStatus midday = grid.statusAt(-34.3944, 150.8983, at(12.5)).orElseThrow();
        GridStatus night = grid.statusAt(-34.3944, 150.8983, at(20)).orElseThrow();

        assertThat(midday.state()).isEqualTo(GridStatus.State.SURPLUS);
        assertThat(midday.supplyKw()).isGreaterThan(midday.demandKw());
        assertThat(midday.priceSignalCkwh()).isLessThan(15);

        assertThat(night.state()).isEqualTo(GridStatus.State.PEAK);
        assertThat(night.supplyKw()).isZero();
        assertThat(night.priceSignalCkwh()).isGreaterThan(midday.priceSignalCkwh());
    }

    @Test
    void tracksGridServiceRatherThanRepeatingIt() {
        // Unanderra is a load centre in GridService, so the status has to be
        // shorter there than in a comparable suburb that is not.
        GridStatus unanderra = grid.statusAt(-34.4496, 150.8501, at(12.5)).orElseThrow();
        GridStatus fairyMeadow = grid.statusAt(-34.3944, 150.8983, at(12.5)).orElseThrow();

        // Status samples a 2 km neighbourhood, and the Unanderra load centre is
        // about 1.5 km across, so the surrounding suburbs pull the aggregate
        // back up. What has to hold is the ordering: the load centre shows up.
        assertThat(unanderra.surplusKw()).isLessThan(fairyMeadow.surplusKw());
        assertThat(unanderra.demandKw() / unanderra.cellCount())
                .isGreaterThan(fairyMeadow.demandKw() / fairyMeadow.cellCount());
        assertThat(fairyMeadow.state()).isEqualTo(GridStatus.State.SURPLUS);
    }

    @Test
    void findsTheCommunityBatteryWhenThereIsOneNearby() {
        GridStatus warrawong = grid.statusAt(-34.4881, 150.8869, at(15)).orElseThrow();
        assertThat(warrawong.communityBattery()).isNotNull();
        assertThat(warrawong.communityBattery().suburb()).isEqualTo("Warrawong");
        assertThat(warrawong.communityBatterySocPct()).isBetween(0, 100);

        GridStatus helensburgh = grid.statusAt(-34.1786, 150.9964, at(15)).orElseThrow();
        assertThat(helensburgh.communityBattery()).isNull();
        assertThat(helensburgh.communityBatterySocPct()).isNull();
    }

    @Test
    void chargesTheBatteryThroughTheDayAndDrawsItDownAtNight() {
        int dawn = grid.statusAt(-34.4881, 150.8869, at(6)).orElseThrow().communityBatterySocPct();
        int afternoon = grid.statusAt(-34.4881, 150.8869, at(15)).orElseThrow().communityBatterySocPct();
        int late = grid.statusAt(-34.4881, 150.8869, at(22)).orElseThrow().communityBatterySocPct();
        assertThat(afternoon).isGreaterThan(dawn);
        assertThat(late).isLessThan(afternoon);
    }

    @Test
    void refusesAnAddressWithNoNetworkNearIt() {
        // Middle of the Tasman Sea, inside the bounding box but not on the grid.
        assertThat(grid.statusAt(-34.45, 151.12, at(12))).isEmpty();
    }

    // --- the plan -------------------------------------------------------------

    @Test
    void givesARenterInAFlatARealPlanForNothingUpfront() {
        OutcomePlan plan = wizard.plan(aisha(), at(12.5)).orElseThrow();

        assertThat(ids(plan)).contains(
                "solar-sharer",           // smart meter, so the free window is open
                "p2p-buy",
                "community-battery",      // Warrawong has one
                "solar-garden-subsidised",// concession card
                "rebate-check",
                "shared-solar-building"); // apartment
        assertThat(plan.totalAnnualLow()).isGreaterThan(1200);
        assertThat(plan.zeroUpfrontCount()).isEqualTo(ids(plan).size());
        // The headline should be the biggest single win, not whatever came first.
        assertThat(plan.doThisWeek()).isNotEmpty();
    }

    @Test
    void routesARenterWithoutASmartMeterToGettingOne() {
        WizardAnswers noMeter = new WizardAnswers("Warrawong", "Warrawong", -34.4881, 150.8869,
                Role.RENTER, HomeType.APARTMENT, Answer.NO, Presence.OFTEN, false,
                Upfront.NO_UPFRONT, false, Answer.NO, null, Answer.NO, false, false, false);
        OutcomePlan plan = wizard.plan(noMeter, at(12.5)).orElseThrow();

        assertThat(ids(plan)).contains("smart-meter-upgrade").doesNotContain("solar-sharer");
        // Still gets a plan worth having.
        assertThat(plan.totalAnnualLow()).isGreaterThan(0);
    }

    @Test
    void turnsNotSureIntoSomethingToGoAndCheck() {
        WizardAnswers unsure = new WizardAnswers("Dapto", "Dapto", -34.5030, 150.7930,
                Role.HOMEOWNER, HomeType.HOUSE, Answer.NOT_SURE, Presence.SOMETIMES, false,
                Upfront.NO_UPFRONT, false, Answer.NOT_SURE, null, Answer.NOT_SURE,
                false, false, false);
        OutcomePlan plan = wizard.plan(unsure, at(12.5)).orElseThrow();

        assertThat(plan.findOutNext()).hasSize(3);
        assertThat(plan.findOutNext().get(0)).contains("meter box");
        assertThat(plan).isNotNull();
    }

    @Test
    void putsAHomeOwnerWithSolarOnTheSellingPath() {
        OutcomePlan plan = wizard.plan(tom(), at(12.5)).orElseThrow();
        assertThat(ids(plan)).contains("p2p-sell", "time-exports");
        assertThat(plan.totalAnnualLow()).isGreaterThan(300);
    }

    @Test
    void givesAnApartmentOwnerTheNoRoofOptionsPlusAVote() {
        WizardAnswers owner = new WizardAnswers("Wollongong", "Wollongong", -34.4244, 150.8938,
                Role.HOMEOWNER, HomeType.APARTMENT, Answer.YES, Presence.SOMETIMES, false,
                Upfront.NO_UPFRONT, false, Answer.NO, null, Answer.NO, false, false, false);
        OutcomePlan plan = wizard.plan(owner, at(12.5)).orElseThrow();

        assertThat(ids(plan)).contains("solar-sharer", "strata-shared-solar");
        assertThat(ids(plan)).doesNotHaveDuplicates();
    }

    @Test
    void givesALandlordTheMultiUnitRebates() {
        OutcomePlan plan = wizard.plan(priya(), at(12.5)).orElseThrow();
        assertThat(ids(plan)).contains("solar-banks-rebate", "apartment-cofund", "split-billing");
    }

    @Test
    void ranksByWhatItIsWorth() {
        OutcomePlan plan = wizard.plan(aisha(), at(12.5)).orElseThrow();
        List<Recommendation> week = plan.doThisWeek();
        for (int i = 1; i < week.size(); i++) {
            int previous = week.get(i - 1).annualLow() + week.get(i - 1).annualHigh();
            int current = week.get(i).annualLow() + week.get(i).annualHigh();
            assertThat(previous).isGreaterThanOrEqualTo(current);
        }
    }

    @Test
    void tailorsTheGridTipToTheStateAndTheRole() {
        OutcomePlan renterMidday = wizard.plan(aisha(), at(12.5)).orElseThrow();
        OutcomePlan renterEvening = wizard.plan(aisha(), at(19)).orElseThrow();
        OutcomePlan sellerEvening = wizard.plan(tom(), at(19)).orElseThrow();

        assertThat(renterMidday.gridTip()).containsIgnoringCase("cheap power");
        assertThat(renterEvening.gridTip()).containsIgnoringCase("dearest power");
        assertThat(sellerEvening.gridTip()).containsIgnoringCase("worth");
        // The community battery reading rides along for anyone near one.
        assertThat(renterMidday.gridTip()).contains("Warrawong");
    }

    @Test
    void neverAsksAboutIncome() {
        // Structural, not a word search: eligibility runs off a concession
        // card, and there is nowhere to put an income even if we wanted one.
        assertThat(WizardAnswers.class.getRecordComponents())
                .noneMatch(c -> c.getName().toLowerCase().contains("income")
                        || c.getName().toLowerCase().contains("earn")
                        || c.getName().toLowerCase().contains("salary"));
    }

    @Test
    void framesEverythingAsAGainRatherThanAHardship() {
        OutcomePlan plan = wizard.plan(aisha(), at(12.5)).orElseThrow();
        String everything = (plan.gridTip() + String.join(" ", plan.maximiseTips())
                + plan.doThisWeek() + plan.doThisMonth()).toLowerCase();
        assertThat(everything)
                .doesNotContain("afford")
                .doesNotContain("poverty")
                .doesNotContain("struggling")
                .doesNotContain("low-income")
                .doesNotContain("disadvantage");
        // "Low Income Household Rebate" is the programme's legal name, so the
        // word is allowed there and only there.
        assertThat(everything.replace("low income household rebate", ""))
                .doesNotContain("income");
    }

    @Test
    void refusesToPlanForAnAddressOffTheNetwork() {
        WizardAnswers offshore = new WizardAnswers("Tasman Sea", "", -34.45, 151.12,
                Role.RENTER, HomeType.HOUSE, Answer.YES, Presence.OFTEN, false,
                Upfront.NO_UPFRONT, false, Answer.NO, null, Answer.NO, false, false, false);
        Optional<OutcomePlan> plan = wizard.plan(offshore, at(12));
        assertThat(plan).isEmpty();
    }
}
