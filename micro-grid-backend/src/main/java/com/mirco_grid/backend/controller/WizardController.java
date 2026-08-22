package com.mirco_grid.backend.controller;

import com.mirco_grid.backend.service.EnergyWizardService;
import com.mirco_grid.backend.service.OutcomePlan;
import com.mirco_grid.backend.service.WizardAnswers;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The Energy Outcome Wizard. */
@RestController
@RequestMapping("/api/wizard")
public class WizardController {

    private static final ZoneId ILLAWARRA = ZoneId.of("Australia/Sydney");

    private final EnergyWizardService wizard;

    public WizardController(EnergyWizardService wizard) {
        this.wizard = wizard;
    }

    /**
     * Build the plan. The client geocodes the address the user typed and sends
     * the coordinates, so this never has to know about suburb names.
     */
    @PostMapping("/plan")
    public OutcomePlan plan(@RequestBody WizardAnswers answers) {
        if (answers.role() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role is required");
        }
        return wizard.plan(answers, ZonedDateTime.now(ILLAWARRA))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No network data near that address - it may be outside the Illawarra."));
    }
}
