package com.fieldops.dashboard;

import com.fieldops.inspection.InspectionSchedulingService;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only aggregates used by the administrative dashboard. */
@Service
public class DashboardService {
  private final JdbcTemplate jdbc;

  public DashboardService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Indicators indicators() {
    EnumMap<InspectionSchedulingService.Status, Long> inspections = new EnumMap<>(InspectionSchedulingService.Status.class);
    for (InspectionSchedulingService.Status status : InspectionSchedulingService.Status.values()) inspections.put(status, 0L);
    jdbc.query("select status,count(*) as total from inspection group by status", rs -> {
      inspections.put(InspectionSchedulingService.Status.valueOf(rs.getString("status")), rs.getLong("total"));
    });

    EnumMap<InspectionSchedulingService.Severity, Long> nonConformities = new EnumMap<>(InspectionSchedulingService.Severity.class);
    for (InspectionSchedulingService.Severity severity : InspectionSchedulingService.Severity.values()) nonConformities.put(severity, 0L);
    jdbc.query("select severity,count(*) as total from non_conformity where status='OPEN' group by severity", rs -> {
      nonConformities.put(InspectionSchedulingService.Severity.valueOf(rs.getString("severity")), rs.getLong("total"));
    });
    return new Indicators(Instant.now(), Map.copyOf(inspections), Map.copyOf(nonConformities));
  }

  public record Indicators(Instant generatedAt, Map<InspectionSchedulingService.Status, Long> inspectionsByStatus,
                           Map<InspectionSchedulingService.Severity, Long> openNonConformitiesBySeverity) {}
}
