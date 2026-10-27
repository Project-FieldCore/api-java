package com.fieldops.user.controller;

import com.fieldops.shared.security.Role;
import com.fieldops.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
  private final UserService users;

  public UserController(UserService users) { this.users = users; }

  @GetMapping
  public UserService.Page list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(defaultValue = "name,asc") String sort,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UserService.Status status,
      @RequestParam(required = false) Role role) {
    return users.list(page, size, sort, q, status, role);
  }

  @GetMapping("/{userId}")
  public UserService.User get(@PathVariable UUID userId) { return users.get(userId); }

  @PostMapping
  public ResponseEntity<UserService.User> create(@Valid @RequestBody CreateUserRequest body) {
    UserService.User user = users.create(new UserService.CreateCommand(
        body.name(), body.email(), body.password(), body.role(), body.phone()));
    return ResponseEntity.created(URI.create("/api/v1/users/" + user.id())).body(user);
  }

  @PatchMapping("/{userId}")
  public UserService.User update(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest body) {
    return users.update(userId, new UserService.UpdateCommand(
        body.name(), body.email(), body.role(), body.phone(), body.version()));
  }

  @PutMapping("/{userId}")
  public UserService.User replace(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest body) {
    return users.update(userId, new UserService.UpdateCommand(
        body.name(), body.email(), body.role(), body.phone(), body.version()));
  }

  @PatchMapping("/{userId}/status")
  public UserService.User changeStatus(
      @PathVariable UUID userId, @Valid @RequestBody StatusChangeRequest body) {
    return users.changeStatus(userId, new UserService.StatusCommand(body.status(), body.version()));
  }

  public record CreateUserRequest(
      @NotBlank @Size(max = 160) String name,
      @NotBlank @Email @Size(max = 320) String email,
      @NotBlank @Size(min = 8, max = 128) String password,
      @NotNull Role role,
      @Size(max = 32) String phone) {}
  public record UpdateUserRequest(
      @Size(max = 160) String name,
      @Email @Size(max = 320) String email,
      Role role,
      @Size(max = 32) String phone,
      @NotNull @Min(0) Integer version) {}
  public record StatusChangeRequest(@NotNull UserService.Status status, @NotNull @Min(0) Integer version) {}
}
