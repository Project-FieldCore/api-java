package com.fieldops.shared.security;

import com.fieldops.shared.web.RequestIdFilter;
import com.fieldops.sync.SyncIdempotencyFilter;
import java.util.Arrays;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(FieldOpsSecurityProperties.class)
public class SecurityConfig {

  static final String[] PUBLIC_ENDPOINTS = {
    "/actuator/health",
    "/swagger-ui.html",
    "/swagger-ui/**",
    "/v3/api-docs/**",
    "/h2-console/**",
    "/api/v1/auth/login",
    "/api/v1/auth/refresh"
  };

  private final ApiAuthenticationEntryPoint authenticationEntryPoint;
  private final ApiAccessDeniedHandler accessDeniedHandler;
  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final SyncIdempotencyFilter syncIdempotencyFilter;
  private final RequestIdFilter requestIdFilter;
  private final FieldOpsSecurityProperties securityProperties;

  public SecurityConfig(
      ApiAuthenticationEntryPoint authenticationEntryPoint,
      ApiAccessDeniedHandler accessDeniedHandler,
      JwtAuthenticationFilter jwtAuthenticationFilter,
      SyncIdempotencyFilter syncIdempotencyFilter,
      RequestIdFilter requestIdFilter,
      FieldOpsSecurityProperties securityProperties) {
    this.authenticationEntryPoint = authenticationEntryPoint;
    this.accessDeniedHandler = accessDeniedHandler;
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    this.syncIdempotencyFilter = syncIdempotencyFilter;
    this.requestIdFilter = requestIdFilter;
    this.securityProperties = securityProperties;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(PUBLIC_ENDPOINTS)
                    .permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .headers(
            headers ->
                headers
                    .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'"))
                    .frameOptions(frame -> frame.deny())
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy
                                    .STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
        .addFilterBefore(requestIdFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(syncIdempotencyFilter, JwtAuthenticationFilter.class)
        .build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    FieldOpsSecurityProperties.Cors cors = securityProperties.getCors();
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(cors.getAllowedOrigins());
    configuration.setAllowedMethods(cors.getAllowedMethods());
    configuration.setAllowedHeaders(cors.getAllowedHeaders());
    configuration.setMaxAge(cors.getMaxAge());
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
