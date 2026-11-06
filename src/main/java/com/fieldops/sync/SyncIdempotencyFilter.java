package com.fieldops.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldops.shared.error.ErrorResponse;
import com.fieldops.shared.security.FieldOpsUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Applies idempotency to retryable mutations sent by the offline technician client. */
@Component
public class SyncIdempotencyFilter extends OncePerRequestFilter {
  public static final String OPERATION_ID = "X-Operation-Id";
  public static final String CLIENT_MUTATION_ID = "X-Client-Mutation-Id";
  private final SyncIdempotencyService idempotency;
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  public SyncIdempotencyFilter(SyncIdempotencyService idempotency) {
    this.idempotency = idempotency;
  }

  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    return !isMutation(request) || !request.getRequestURI().startsWith("/api/v1/inspections/my/");
  }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<String> key = operationKey(request);
    if (key.isEmpty()) { chain.doFilter(request, response); return; }
    if (key.get().length() > 255) {
      writeError(response, request, 400, "IDEMPOTENCY_KEY_INVALID", "Operation id must contain at most 255 characters");
      return;
    }
    String other = request.getHeader(CLIENT_MUTATION_ID);
    if (request.getHeader(OPERATION_ID) != null && other != null && !key.get().equals(other.trim())) {
      writeError(response, request, 400, "IDEMPOTENCY_KEY_MISMATCH", "Operation id and client mutation id must match when both are sent");
      return;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication != null && authentication.getPrincipal() instanceof FieldOpsUserPrincipal principal)) {
      chain.doFilter(request, response); return;
    }
    SyncIdempotencyService.Claim claim = idempotency.claim(principal.userId(), key.get());
    if (!claim.owner()) {
      if (claim.stored() != null && claim.stored().status() != null) {
        response.setStatus(claim.stored().status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(claim.stored().body());
        return;
      }
      writeError(response, request, 409, "OPERATION_IN_PROGRESS", "An operation with this id is already in progress");
      return;
    }
    ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
    try {
      chain.doFilter(request, wrapped);
      String body = new String(wrapped.getContentAsByteArray(), wrapped.getCharacterEncoding());
      if (wrapped.getStatus() >= 200 && wrapped.getStatus() < 300) idempotency.complete(principal.userId(), key.get(), wrapped.getStatus(), body);
      else idempotency.discard(principal.userId(), key.get());
    } catch (RuntimeException | IOException | ServletException ex) {
      idempotency.discard(principal.userId(), key.get());
      throw ex;
    } finally { wrapped.copyBodyToResponse(); }
  }

  private Optional<String> operationKey(HttpServletRequest request) {
    String operationId = request.getHeader(OPERATION_ID);
    String clientMutationId = request.getHeader(CLIENT_MUTATION_ID);
    String value = operationId == null || operationId.isBlank() ? clientMutationId : operationId;
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
  }
  private static boolean isMutation(HttpServletRequest request) {
    return HttpMethod.POST.matches(request.getMethod()) || HttpMethod.PUT.matches(request.getMethod()) || HttpMethod.PATCH.matches(request.getMethod());
  }
  private void writeError(HttpServletResponse response, HttpServletRequest request, int status, String code, String message) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), new ErrorResponse(Instant.now(), status, code, message, request.getRequestURI(), MDC.get("requestId"), null));
  }
}
