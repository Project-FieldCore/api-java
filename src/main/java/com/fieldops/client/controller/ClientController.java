package com.fieldops.client.controller;

import com.fieldops.client.ClientService;
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
@RequestMapping("/api/v1/clients")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class ClientController {
  private final ClientService clients;

  public ClientController(ClientService clients) { this.clients = clients; }

  @GetMapping
  public ClientService.Page list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return clients.list(page, size);
  }

  @GetMapping("/{clientId}")
  public ClientService.Client get(@PathVariable UUID clientId) { return clients.get(clientId); }

  @PostMapping
  public ResponseEntity<ClientService.Client> create(@Valid @RequestBody CreateClientRequest body) {
    ClientService.Client client = clients.create(new ClientService.CreateCommand(
        body.name(), body.legalName(), body.document(), body.email(), body.phone()));
    return ResponseEntity.created(URI.create("/api/v1/clients/" + client.id())).body(client);
  }

  @PatchMapping("/{clientId}")
  public ClientService.Client update(@PathVariable UUID clientId, @Valid @RequestBody UpdateClientRequest body) {
    return clients.update(clientId, new ClientService.UpdateCommand(
        body.name(), body.legalName(), body.document(), body.email(), body.phone(), body.version()));
  }

  @PutMapping("/{clientId}")
  public ClientService.Client replace(@PathVariable UUID clientId, @Valid @RequestBody UpdateClientRequest body) {
    return update(clientId, body);
  }

  @PatchMapping("/{clientId}/status")
  public ClientService.Client changeStatus(@PathVariable UUID clientId, @Valid @RequestBody StatusChangeRequest body) {
    return clients.changeStatus(clientId, new ClientService.StatusCommand(body.status(), body.version()));
  }

  public record CreateClientRequest(@NotBlank @Size(max = 160) String name, @Size(max = 255) String legalName,
                                    @Size(max = 64) String document, @Email @Size(max = 320) String email,
                                    @Size(max = 32) String phone) {}
  public record UpdateClientRequest(@Size(max = 160) String name, @Size(max = 255) String legalName,
                                    @Size(max = 64) String document, @Email @Size(max = 320) String email,
                                    @Size(max = 32) String phone, @NotNull @Min(0) Integer version) {}
  public record StatusChangeRequest(@NotNull ClientService.Status status, @NotNull @Min(0) Integer version) {}
}
