package com.fieldops.site.controller;

import com.fieldops.site.SiteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class SiteController {
  private final SiteService sites;

  public SiteController(SiteService sites) { this.sites = sites; }

  @GetMapping("/clients/{clientId}/sites")
  public List<SiteService.Site> listByClient(@PathVariable UUID clientId) { return sites.listByClient(clientId); }

  @PostMapping("/clients/{clientId}/sites")
  public ResponseEntity<SiteService.Site> create(@PathVariable UUID clientId, @Valid @RequestBody SiteRequest body) {
    SiteService.Site site = sites.create(clientId, body.createCommand());
    return ResponseEntity.created(URI.create("/api/v1/sites/" + site.id())).body(site);
  }

  @GetMapping("/sites")
  public List<SiteService.Site> list() { return sites.list(); }

  @GetMapping("/sites/{siteId}")
  public SiteService.Site get(@PathVariable UUID siteId) { return sites.get(siteId); }

  @PatchMapping("/sites/{siteId}")
  public SiteService.Site update(@PathVariable UUID siteId, @Valid @RequestBody SiteUpdateRequest body) {
    return sites.update(siteId, body.command());
  }

  @PutMapping("/sites/{siteId}")
  public SiteService.Site replace(@PathVariable UUID siteId, @Valid @RequestBody SiteUpdateRequest body) {
    return sites.update(siteId, body.command());
  }

  @PatchMapping("/sites/{siteId}/status")
  public SiteService.Site changeStatus(@PathVariable UUID siteId, @Valid @RequestBody StatusChangeRequest body) {
    return sites.changeStatus(siteId, new SiteService.StatusCommand(body.status(), body.version()));
  }

  @DeleteMapping("/sites/{siteId}")
  public ResponseEntity<Void> delete(@PathVariable UUID siteId, @Valid @RequestBody DeleteRequest body) {
    sites.changeStatus(siteId, new SiteService.StatusCommand(SiteService.Status.INACTIVE, body.version()));
    return ResponseEntity.noContent().build();
  }

  public record SiteRequest(@NotBlank @Size(max = 160) String name, @Size(max = 1000) String description,
                            @Size(max = 255) String addressLine, @Size(max = 120) String city, @Size(max = 120) String state,
                            @Size(max = 32) String postalCode, @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
                            @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude, @Size(max = 160) String contactName,
                            @Size(max = 32) String contactPhone) {
    SiteService.CreateCommand createCommand() { return new SiteService.CreateCommand(name, description, addressLine, city, state, postalCode, latitude, longitude, contactName, contactPhone); }
  }
  public record SiteUpdateRequest(@Size(max = 160) String name, @Size(max = 1000) String description,
                                  @Size(max = 255) String addressLine, @Size(max = 120) String city, @Size(max = 120) String state,
                                  @Size(max = 32) String postalCode, @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
                                  @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude, @Size(max = 160) String contactName,
                                  @Size(max = 32) String contactPhone, @NotNull @Min(0) Integer version) {
    SiteService.UpdateCommand command() { return new SiteService.UpdateCommand(name, description, addressLine, city, state, postalCode, latitude, longitude, contactName, contactPhone, version); }
  }
  public record StatusChangeRequest(@NotNull SiteService.Status status, @NotNull @Min(0) Integer version) {}
  public record DeleteRequest(@NotNull @Min(0) Integer version) {}
}
