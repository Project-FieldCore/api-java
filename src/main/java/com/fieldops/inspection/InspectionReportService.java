package com.fieldops.inspection;

import com.fieldops.shared.error.ApiException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Produz um PDF básico e determinístico para uma inspeção já aprovada. */
@Service
public class InspectionReportService {
  private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
  private final JdbcTemplate jdbc;

  public InspectionReportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public byte[] approvedInspectionPdf(UUID inspectionId) {
    List<ReportData> results = jdbc.query("""
        select i.id,i.status,i.title,i.priority,i.scheduled_for,i.submitted_at_server,i.approved_at,
               c.name client_name,s.name site_name,u.name technician_name,ir.comment review_comment,
               (select count(*) from inspection_item_snapshot snapshot where snapshot.inspection_id=i.id) item_count,
               (select count(*) from evidence e join inspection_response response on response.id=e.inspection_response_id where response.inspection_id=i.id) evidence_count,
               (select count(*) from non_conformity nc where nc.inspection_id=i.id) non_conformity_count
        from inspection i
        join client c on c.id=i.client_id
        join inspection_site s on s.id=i.site_id
        join app_user u on u.id=i.technician_id
        left join inspection_review ir on ir.inspection_id=i.id and ir.decision='APPROVED'
        where i.id=?
        """, (rs, row) -> new ReportData(
            UUID.fromString(rs.getString("id")), rs.getString("status"), rs.getString("title"), rs.getString("priority"),
            timestamp(rs.getTimestamp("scheduled_for")), timestamp(rs.getTimestamp("submitted_at_server")), timestamp(rs.getTimestamp("approved_at")),
            rs.getString("client_name"), rs.getString("site_name"), rs.getString("technician_name"), rs.getString("review_comment"),
            rs.getInt("item_count"), rs.getInt("evidence_count"), rs.getInt("non_conformity_count")), inspectionId);
    if (results.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "INSPECTION_NOT_FOUND", "Inspection not found");
    ReportData report = results.getFirst();
    if (!"APPROVED".equals(report.status())) {
      throw new ApiException(HttpStatus.CONFLICT, "INSPECTION_REPORT_NOT_AVAILABLE", "A report is available only for an approved inspection");
    }
    return Pdf.document(lines(report));
  }

  private static Instant timestamp(Timestamp value) { return value == null ? null : value.toInstant(); }
  private static String date(Instant value) { return value == null ? "Not recorded" : DATE_TIME.format(value); }

  private static List<String> lines(ReportData data) {
    List<String> lines = new ArrayList<>(List.of(
        "FieldOps - Inspection report",
        "Inspection: " + data.id(),
        "Title: " + data.title(),
        "Status: APPROVED",
        "Client: " + data.clientName(),
        "Site: " + data.siteName(),
        "Technician: " + data.technicianName(),
        "Priority: " + data.priority(),
        "Scheduled: " + date(data.scheduledFor()),
        "Submitted: " + date(data.submittedAt()),
        "Approved: " + date(data.approvedAt()),
        "Checklist items: " + data.itemCount(),
        "Evidence files: " + data.evidenceCount(),
        "Nonconformities: " + data.nonConformityCount()));
    if (data.reviewComment() != null && !data.reviewComment().isBlank()) lines.add("Review comment: " + data.reviewComment());
    return lines;
  }

  private record ReportData(UUID id, String status, String title, String priority, Instant scheduledFor, Instant submittedAt,
                            Instant approvedAt, String clientName, String siteName, String technicianName,
                            String reviewComment, int itemCount, int evidenceCount, int nonConformityCount) {}

  private static final class Pdf {
    private Pdf() {}

    static byte[] document(List<String> lines) {
      StringBuilder content = new StringBuilder("BT\n/F1 16 Tf\n50 800 Td\n(FieldOps Inspection Report) Tj\n/F1 10 Tf\n0 -28 Td\n");
      for (String line : lines.subList(1, lines.size())) content.append('(').append(escape(line)).append(") Tj\n0 -16 Td\n");
      content.append("ET\n");
      List<byte[]> objects = List.of(
          bytes("<< /Type /Catalog /Pages 2 0 R >>"),
          bytes("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
          bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"),
          bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
          bytes("<< /Length " + bytes(content.toString()).length + " >>\nstream\n" + content + "endstream"));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      write(out, "%PDF-1.4\n%âãÏÓ\n");
      List<Integer> offsets = new ArrayList<>();
      offsets.add(0);
      for (int i = 0; i < objects.size(); i++) {
        offsets.add(out.size()); write(out, (i + 1) + " 0 obj\n"); out.writeBytes(objects.get(i)); write(out, "\nendobj\n");
      }
      int xref = out.size(); write(out, "xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
      for (int i = 1; i < offsets.size(); i++) write(out, String.format("%010d 00000 n \n", offsets.get(i)));
      write(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
      return out.toByteArray();
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.ISO_8859_1); }
    private static void write(ByteArrayOutputStream out, String value) { out.writeBytes(bytes(value)); }
    private static String escape(String value) {
      String ascii = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^\\x20-\\x7E]", "?");
      return ascii.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }
  }
}
