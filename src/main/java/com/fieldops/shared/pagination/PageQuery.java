package com.fieldops.shared.pagination;

import com.fieldops.shared.error.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

public final class PageQuery {
  private PageQuery() {}

  public static String orderBy(String sort, Map<String, String> allowed) {
    String[] values = sort.split(",", -1);
    String column = allowed.get(values[0]);
    if (column == null || values.length > 2 || (values.length == 2 && !"asc".equalsIgnoreCase(values[1]) && !"desc".equalsIgnoreCase(values[1]))) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT", "Sort must use an allowed field and asc or desc direction");
    }
    String direction = values.length == 2 ? values[1].toUpperCase() : "ASC";
    return column + " " + direction + ", id ASC";
  }
}
