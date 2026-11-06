package com.fieldops.sync;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Persists the result of a mobile mutation so retries do not execute it twice. */
@Service
public class SyncIdempotencyService {
  private final JdbcTemplate jdbc;

  public SyncIdempotencyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Claim claim(UUID userId, String key) {
    jdbc.update("delete from sync_idempotency where expires_at < current_timestamp");
    try {
      jdbc.update("insert into sync_idempotency (id,user_id,idempotency_key,expires_at) values (?,?,?,?)",
          UUID.randomUUID(), userId, key, java.sql.Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS)));
      return Claim.claimed();
    } catch (DuplicateKeyException ignored) {
      StoredResponse stored = jdbc.query("select response_status,response_body from sync_idempotency where user_id=? and idempotency_key=?",
          (rs, row) -> new StoredResponse((Integer) rs.getObject("response_status"), rs.getString("response_body")), userId, key)
          .stream().findFirst().orElse(null);
      return stored == null ? Claim.inProgress() : Claim.existing(stored);
    }
  }

  public void complete(UUID userId, String key, int status, String body) {
    jdbc.update("update sync_idempotency set response_status=?,response_body=?,expires_at=? where user_id=? and idempotency_key=?",
        status, body, java.sql.Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS)), userId, key);
  }

  public void discard(UUID userId, String key) {
    jdbc.update("delete from sync_idempotency where user_id=? and idempotency_key=? and response_status is null", userId, key);
  }

  public record Claim(boolean owner, StoredResponse stored) {
    static Claim claimed() { return new Claim(true, null); }
    static Claim existing(StoredResponse stored) { return new Claim(false, stored); }
    static Claim inProgress() { return new Claim(false, null); }
  }
  public record StoredResponse(Integer status, String body) {}
}
