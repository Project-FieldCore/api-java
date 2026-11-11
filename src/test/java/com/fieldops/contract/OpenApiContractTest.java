package com.fieldops.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    assertTrue(contract.contains("/api/v1/auth/logout:"));
    assertTrue(contract.contains("/api/v1/auth/me:"));
    assertTrue(contract.contains("/api/v1/users:"));
    assertTrue(contract.contains("/api/v1/users/{userId}/status:"));
    assertTrue(contract.contains("Requires the ADMIN role."));
    assertTrue(contract.contains("/api/v1/clients:"));
    assertTrue(contract.contains("/api/v1/clients/{clientId}/sites:"));
    assertTrue(contract.contains("/api/v1/sites:"));
    assertTrue(contract.contains("/api/v1/sites/{siteId}/status:"));
    assertTrue(contract.contains("SiteCreateInput:"));
    assertTrue(contract.contains("/api/v1/equipment:"));
    assertTrue(contract.contains("/api/v1/template-sections/{sectionId}/items/{itemId}:"));
    assertTrue(contract.contains("TemplateItemUpdateRequest:"));
    assertTrue(contract.contains("draftVersionId:"));
    assertTrue(contract.contains("/api/v1/mobile/sync/push:"));
    assertTrue(contract.contains("/api/v1/mobile/sync/pull:"));
    assertTrue(contract.contains("MobileSyncPullResponse:"));
    assertTrue(contract.contains("DEPENDENCY_FAILED"));
    assertTrue(contract.contains("InspectionPriority:"));
    assertTrue(contract.contains("scheduledFor:"));
    assertTrue(contract.contains("instructions:"));
    assertTrue(contract.contains("/api/v1/inspections/{inspectionId}/monitoring:"));
    assertTrue(contract.contains("InspectionMonitoring:"));
    assertTrue(contract.contains("itemTitle:"));
    assertTrue(contract.contains("image/png"));
    assertTrue(contract.contains("image/webp"));
    assertTrue(contract.contains("/api/v1/inspections/{inspectionId}/cancel:"));
    assertTrue(contract.contains("CancelInspectionRequest:"));
    assertTrue(contract.contains("InspectionPage:"));
    assertTrue(contract.contains("/api/v1/inspections/my/{inspectionId}/checklist:"));
    assertTrue(contract.contains("/api/v1/inspections/{inspectionId}/checklist:"));
    assertTrue(contract.contains("valueNumber:"));
    assertTrue(contract.contains("/api/v1/inspections/my/{inspectionId}/confirm-equipment:"));
    assertTrue(contract.contains("ConfirmEquipmentRequest:"));
    assertTrue(contract.contains("/api/v1/inspections/my/{inspectionId}/start:"));
    assertTrue(contract.contains("/api/v1/inspections/my/{inspectionId}/items/{itemId}/evidence:"));
    assertTrue(contract.contains("EvidenceFile:"));
    assertTrue(contract.contains("/api/v1/inspections/my/{inspectionId}/non-conformities:"));
    assertTrue(contract.contains("NonConformityRequest:"));
    assertTrue(contract.contains("/api/v1/inspections/my/{inspectionId}/complete:"));
    assertTrue(contract.contains("/api/v1/inspections/review-queue:"));
    assertTrue(contract.contains("/api/v1/inspections/{inspectionId}/review/start:"));
    assertTrue(contract.contains("/api/v1/inspections/{inspectionId}/review/approve:"));
    assertTrue(contract.contains("ApproveInspectionRequest:"));
    assertTrue(contract.contains("REVIEW_STARTED"));
    assertTrue(contract.contains("REVIEW_APPROVED"));
    assertTrue(contract.contains("ReviewQueuePage:"));
    assertTrue(contract.contains("X-Operation-Id"));
    assertTrue(contract.contains("X-Client-Mutation-Id"));
    assertTrue(contract.contains("AssignedInspection:"));
    assertTrue(contract.contains("scheduledFrom"));
    assertTrue(contract.contains("ErrorResponse:"));
    assertTrue(contract.contains("fieldErrors:"));
    assertTrue(contract.contains("AuthProfile:"));
    assertTrue(contract.contains("RefreshTokenRequest"));
    assertTrue(contract.contains("'403': {$ref: '#/components/responses/Forbidden'}"));
    assertTrue(contract.contains("'204': {description: Logged out}"));
    assertTrue(contract.contains("#/components/schemas/AuthProfile"));
    assertFalse(contract.contains("reset-password"));
    assertFalse(contract.contains("PasswordResetRequest"));
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
