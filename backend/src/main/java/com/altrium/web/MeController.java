package com.altrium.web;

import com.altrium.auth.CurrentUserService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the caller is, and where the frontend should send them after login (feature 1).
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final CurrentUserService currentUser;

    public MeController(CurrentUserService currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "The authenticated user's identity, roles and landing route")
    public MeResponse me() {
        // require() rather than find(): a token that validates but resolves to nobody is a
        // 403, not an empty response body.
        return MeResponse.from(currentUser.require());
    }
}
