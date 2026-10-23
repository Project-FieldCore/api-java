package com.fieldops.shared.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fieldops.security")
public class FieldOpsSecurityProperties {

  private Jwt jwt = new Jwt();
  private Cors cors = new Cors();

  public Jwt getJwt() {
    return jwt;
  }

  public void setJwt(Jwt jwt) {
    this.jwt = jwt;
  }

  public Cors getCors() {
    return cors;
  }

  public void setCors(Cors cors) {
    this.cors = cors;
  }

  public static class Jwt {
    private String secret;
    private Duration accessTtl = Duration.ofMinutes(15);
    private Duration refreshTtl = Duration.ofDays(7);

    public String getSecret() {
      return secret;
    }

    public void setSecret(String secret) {
      this.secret = secret;
    }

    public Duration getAccessTtl() {
      return accessTtl;
    }

    public void setAccessTtl(Duration accessTtl) {
      this.accessTtl = accessTtl;
    }

    public Duration getRefreshTtl() {
      return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
      this.refreshTtl = refreshTtl;
    }
  }

  public static class Cors {
    private List<String> allowedOrigins = List.of("http://localhost:3000");
    private List<String> allowedMethods =
        List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private List<String> allowedHeaders =
        List.of("Authorization", "Content-Type", "X-Request-Id");
    private long maxAge = 3600;

    public List<String> getAllowedOrigins() {
      return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
      this.allowedOrigins = allowedOrigins;
    }

    public List<String> getAllowedMethods() {
      return allowedMethods;
    }

    public void setAllowedMethods(List<String> allowedMethods) {
      this.allowedMethods = allowedMethods;
    }

    public List<String> getAllowedHeaders() {
      return allowedHeaders;
    }

    public void setAllowedHeaders(List<String> allowedHeaders) {
      this.allowedHeaders = allowedHeaders;
    }

    public long getMaxAge() {
      return maxAge;
    }

    public void setMaxAge(long maxAge) {
      this.maxAge = maxAge;
    }
  }
}
