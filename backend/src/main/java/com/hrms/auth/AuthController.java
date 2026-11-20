package com.hrms.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    public record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, OffsetDateTime expiresAt, CurrentUser user) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword,
            @NotBlank @Size(max = 200) String newPassword) {
    }

    private final AuthService auth;
    private final JdbcClient jdbc;

    public AuthController(AuthService auth, JdbcClient jdbc) {
        this.auth = auth;
        this.jdbc = jdbc;
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        AuthService.LoginResult r = auth.login(body.username(), body.password(), request.getRemoteAddr());
        return new LoginResponse(r.token(), "Bearer", r.principal().expiresAt(), currentUser(r.principal()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal HrmsPrincipal principal, HttpServletRequest request) {
        auth.logout(principal, request.getRemoteAddr());
    }

    @GetMapping("/me")
    CurrentUser me(@AuthenticationPrincipal HrmsPrincipal principal) {
        return currentUser(principal);
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@AuthenticationPrincipal HrmsPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        auth.changePassword(principal, body.currentPassword(), body.newPassword(), request.getRemoteAddr());
    }

    private CurrentUser currentUser(HrmsPrincipal p) {
        String[] row = jdbc.sql("SELECT emp_number, first_name, last_name FROM hrms.employees WHERE emp_id = :id")
                .param("id", p.empId())
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}).single();
        Map<Module, List<Action>> perms = new EnumMap<>(Module.class);
        for (Module m : Module.values()) {
            List<Action> actions = new ArrayList<>();
            for (Action a : Action.values()) {
                if (p.has(m, a)) {
                    actions.add(a);
                }
            }
            if (!actions.isEmpty()) {
                perms.put(m, actions);
            }
        }
        return new CurrentUser(p.empId(), row[0], p.username(), row[1], row[2], p.loginTime(), p.expiresAt(), perms);
    }
}
