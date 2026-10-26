package com.fieldops.shared.security;

import com.fieldops.auth.AuthService;
import io.jsonwebtoken.JwtException;
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

/**
 * Sprint 1 skeleton: reads Bearer header but does not authenticate yet.
 * Protected routes remain unauthenticated until Sprint 2 token validation.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
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
        var principal = new FieldOpsUserPrincipal(user.id(), Set.of(Role.valueOf(user.role())), Set.of());
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, Set.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (JwtException | IllegalArgumentException ignored) {
        // The entry point emits the standardized 401 response for protected routes.
      }
    }
    filterChain.doFilter(request, response);
  }
}
