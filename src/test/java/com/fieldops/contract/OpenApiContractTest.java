package com.fieldops.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpenApiContractTest {

  private static final Path CONTRACT = Path.of("openapi", "fieldops-v1.yaml");
  private static final Path MOCKS = Path.of("openapi", "mocks", "fieldops-v1.json");

  @Test
  void contractCoversTheInitialApiAndStandardErrorShape() throws Exception {
    String contract = Files.readString(CONTRACT);

    assertTrue(contract.contains("/api/v1/auth/login:"));
    assertTrue(contract.contains("/api/v1/users:"));
    assertTrue(contract.contains("/api/v1/clients:"));
    assertTrue(contract.contains("/api/v1/sites:"));
    assertTrue(contract.contains("/api/v1/equipment:"));
    assertTrue(contract.contains("ErrorResponse:"));
    assertTrue(contract.contains("fieldErrors:"));
  }

  @Test
  void mocksAreValidJsonAndFollowContractResourceGroups() throws Exception {
    var mocks = new ObjectMapper().readTree(Files.readString(MOCKS));

    assertTrue(mocks.path("users").isArray());
    assertTrue(mocks.path("clients").isArray());
    assertTrue(mocks.path("sites").isArray());
    assertTrue(mocks.path("equipment").isArray());
  }
}
