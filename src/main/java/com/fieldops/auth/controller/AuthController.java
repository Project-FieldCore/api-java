package com.fieldops.auth.controller;

import com.fieldops.auth.AuthService;
import com.fieldops.shared.security.FieldOpsUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @PostMapping("/login")
  public AuthService.Tokens login(@Valid @RequestBody LoginRequest body) {
    return auth.login(body.email(), body.password());
  }

  @PostMapping("/refresh")
  public AuthService.Tokens refresh(@Valid @RequestBody RefreshRequest body) {
    return auth.refresh(body.refreshToken());
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@Valid @RequestBody RefreshRequest body) {
    auth.logout(body.refreshToken());
  }

  @GetMapping("/me")
  public AuthService.Profile me(@AuthenticationPrincipal FieldOpsUserPrincipal principal) {
    return auth.profile(principal.userId());
  }

  public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

  public record RefreshRequest(@NotBlank String refreshToken) {}
}
