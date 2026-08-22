package com.mirco_grid.backend.controller;


import com.mirco_grid.backend.service.SolarSnapshot;
import com.mirco_grid.backend.service.WeatherDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TestController {

    private final WeatherDataService weatherDataService;

    public TestController(WeatherDataService weatherDataService) {
        this.weatherDataService = weatherDataService;
    }

    @GetMapping("/solar/{postcode}")
    ResponseEntity<SolarSnapshot> solar(@PathVariable String postcode) {
        return weatherDataService.getCombinedSolarData(postcode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

}
