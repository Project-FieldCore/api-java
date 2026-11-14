package com.fieldops.push;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service public class PushSubscriptionService {
  private final JdbcTemplate jdbc; public PushSubscriptionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  public Subscription register(UUID technicianId, String token, boolean granted) {
    var rows=jdbc.query("select id,technician_id from push_subscription where token=?",(rs,n)->new Row(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2))),token.trim());
    UUID id=rows.isEmpty()?UUID.randomUUID():rows.getFirst().id();
    if(rows.isEmpty()) jdbc.update("insert into push_subscription (id,technician_id,token,enabled) values (?,?,?,?)",id,technicianId,token.trim(),granted);
    else { if(!rows.getFirst().technicianId().equals(technicianId)) throw new com.fieldops.shared.error.ApiException(org.springframework.http.HttpStatus.CONFLICT,"PUSH_TOKEN_IN_USE","Push token is already registered"); jdbc.update("update push_subscription set enabled=?,updated_at=current_timestamp where id=?",granted,id); }
    return new Subscription(id,granted);
  }
  public record Subscription(UUID id, boolean enabled) {} private record Row(UUID id,UUID technicianId) {}
}
