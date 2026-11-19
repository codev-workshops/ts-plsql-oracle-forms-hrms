package com.hrms.platform.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) { }

    public record LoginResponse(String token, CurrentUser user) { }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) { }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AuthService.LoginResult result = auth.login(request.username(), request.password(), http.getRemoteAddr());
        return new LoginResponse(result.token(), result.user());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(CurrentUser user) {
        auth.logout(user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public CurrentUser me(CurrentUser user) {
        return user;
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(CurrentUser user, @Valid @RequestBody ChangePasswordRequest request) {
        auth.changePassword(user, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
