package com.mirco_grid.backend.service;

/**
 * What the wizard asked and what came back.
 *
 * <p>Every field is optional in the sense that "not sure" is always a legal
 * answer - the plan still gets built, and the unknowns turn into "find this
 * out" steps rather than dead ends.
 */
public record WizardAnswers(
        /** What the user typed at step 0, kept for the plan heading. */
        String address,
        String suburb,
        double lat,
        double lng,
        Role role,

        // Step 2 - shared across roles where the spec asks the same thing
        /** R1 / H1 / L1. */
        HomeType homeType,
        /** R2 / H4. */
        Answer smartMeter,
        /** R3 / H5. */
        Presence daytimePresence,
        /** R4 / H7 - asked as a concession card, never as income. */
        boolean concessionCard,
        /** R5. */
        Upfront upfrontPreference,
        /** R6. */
        boolean bigAppliances,

        // HomeOwner / Landlord
        /** H2 / L2. */
        Answer hasSolar,
        Double solarKw,
        /** H3. */
        Answer hasBattery,
        /** H6. */
        boolean wantsToSellSurplus,
        /** L3. */
        boolean openToSubsidisedSolar,
        /** L4. */
        boolean wouldOfferSolarToTenants) {

    public enum Role { RENTER, HOMEOWNER, LANDLORD }

    public enum HomeType { HOUSE, APARTMENT, TOWNHOUSE, SINGLE_DWELLING, MULTI_UNIT }

    /** "Not sure" is a first-class answer everywhere it is offered. */
    public enum Answer { YES, NO, NOT_SURE }

    public enum Presence { OFTEN, SOMETIMES, RARELY }

    public enum Upfront { NO_UPFRONT, OPEN_TO_INVESTMENT }

    public boolean hasSmartMeter() {
        return smartMeter == Answer.YES;
    }

    /** No roof of their own: renters, and anyone in an apartment. */
    public boolean withoutRoof() {
        return role == Role.RENTER
                || homeType == HomeType.APARTMENT
                || homeType == HomeType.MULTI_UNIT;
    }

    public boolean isApartment() {
        return homeType == HomeType.APARTMENT || homeType == HomeType.MULTI_UNIT;
    }
}
