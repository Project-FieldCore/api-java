package com.fieldops.shared.export;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** CSV UTF-8 with RFC-4180 quoting and spreadsheet formula injection protection. */
public final class CsvExporter {
  private CsvExporter() {}

  public static ResponseEntity<byte[]> download(String filename, List<String> headers, List<? extends List<?>> rows) {
    StringBuilder csv = new StringBuilder("\uFEFF");
    append(csv, headers);
    for (List<?> row : rows) append(csv, row);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
        .body(csv.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void append(StringBuilder csv, List<?> values) {
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) csv.append(',');
      String value = values.get(i) == null ? "" : String.valueOf(values.get(i));
      if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
      csv.append('"').append(value.replace("\"", "\"\"")).append('"');
    }
    csv.append("\r\n");
  }
}
