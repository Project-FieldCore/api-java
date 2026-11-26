package com.fieldops.shared.security;

import com.fieldops.auth.AuthService;
import com.fieldops.shared.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fieldops.shared.error.ErrorResponse;
import io.jsonwebtoken.JwtException;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Validates bearer JWTs and exposes the active user to protected routes. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final AuthService auth;
  public JwtAuthenticationFilter(@Lazy AuthService auth) { this.auth = auth; }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
      try {
        var user = auth.authenticatedUser(header.substring(7));
        if (user.mustChangePassword() && !isAllowedWhilePasswordChangePending(request)) {
          writePasswordChangeRequired(request, response);
          return;
        }
        var principal = new FieldOpsUserPrincipal(user.id(), Set.of(Role.valueOf(user.role())), Set.of());
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, Set.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (JwtException | IllegalArgumentException | ApiException ignored) {
        // The entry point emits the standardized 401 response for protected routes.
      }
    }
    filterChain.doFilter(request, response);
  }

  /** Enquanto a troca de senha do primeiro acesso está pendente, só estas rotas respondem. */
  private static boolean isAllowedWhilePasswordChangePending(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.equals("/api/v1/auth/change-password")
        || path.equals("/api/v1/auth/logout")
        || path.equals("/api/v1/auth/me");
  }

  private static void writePasswordChangeRequired(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var body = new ErrorResponse(
        Instant.now(), 403, "PASSWORD_CHANGE_REQUIRED", "Password change required before using the system",
        request.getRequestURI(), MDC.get("requestId"), null);
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    MAPPER.writeValue(response.getOutputStream(), body);
  }
}
