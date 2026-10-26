package com.fieldops.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
  }

  @Autowired private DataSource dataSource;

  @Test
  void appliesMigrationsAndEnforcesRequiredUniqueConstraints() throws Exception {
    assertThat(tableExists("app_user")).isTrue();
    assertThat(tableExists("inspection_response")).isTrue();
    assertThat(columnExists("refresh_token", "family_id")).isTrue();
    assertThat(constraintExists("uk_app_user_email")).isTrue();
    assertThat(constraintExists("uk_equipment_qr_code")).isTrue();
    assertThat(constraintExists("uk_inspection_template_version")).isTrue();
    assertThat(constraintExists("uk_inspection_response_item")).isTrue();

    UUID userId = UUID.randomUUID();
    UUID secondUserId = UUID.randomUUID();
    insertUser(userId, "unique@example.com");
    assertThatThrownBy(() -> insertUser(secondUserId, "unique@example.com"))
        .isInstanceOf(SQLException.class);
  }

  private boolean constraintExists(String constraintName) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = ?)")) {
      statement.setString(1, constraintName);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }

  private boolean tableExists(String tableName) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement("SELECT to_regclass(?)")) {
      statement.setString(1, "public." + tableName);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getString(1) != null;
      }
    }
  }

  private boolean columnExists(String tableName, String columnName) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?)")) {
      statement.setString(1, tableName);
      statement.setString(2, columnName);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }

  private void insertUser(UUID id, String email) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "INSERT INTO app_user (id, email, name, password_hash, role) VALUES (?, ?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, email);
      statement.setString(3, "Migration test");
      statement.setString(4, "not-a-secret");
      statement.setString(5, "ADMIN");
      statement.executeUpdate();
    }
  }
}
