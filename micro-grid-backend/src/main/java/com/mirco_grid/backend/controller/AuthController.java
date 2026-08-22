package com.mirco_grid.backend.controller;

import com.mirco_grid.backend.service.council.CouncilApi.LoginRequest;
import com.mirco_grid.backend.service.council.CouncilApi.LoginResponse;
import com.mirco_grid.backend.service.council.CouncilAuth;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Sign-in for the council view. Everything else on this API is public. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CouncilAuth auth;

    public AuthController(CouncilAuth auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        return auth.login(request.username(), request.password())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Wrong username or password."));
    }
}
