package com.fieldops.push;
import com.fieldops.shared.security.FieldOpsUserPrincipal;
import jakarta.validation.Valid; import jakarta.validation.constraints.NotBlank; import jakarta.validation.constraints.NotNull; import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize; import org.springframework.security.core.annotation.AuthenticationPrincipal; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/mobile/push-subscriptions") public class PushSubscriptionController {
 private final PushSubscriptionService service; public PushSubscriptionController(PushSubscriptionService service){this.service=service;}
 @PutMapping @PreAuthorize("hasRole('TECHNICIAN')") public PushSubscriptionService.Subscription register(@Valid @RequestBody Request body,@AuthenticationPrincipal FieldOpsUserPrincipal principal){return service.register(principal.userId(),body.token(),body.permissionGranted());}
 public record Request(@NotBlank @Size(max=512) String token,@NotNull Boolean permissionGranted) {}
}
