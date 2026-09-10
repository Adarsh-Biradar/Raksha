package com.fraudshield;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Surfaces stuck fraud/notification pipelines (dead scoring jobs, failing SMS/email delivery) via /actuator/health.
@Component
public class BacklogHealthIndicator implements HealthIndicator {
 private static final long DEAD_JOBS_THRESHOLD=50;
 private static final long FAILED_DELIVERIES_THRESHOLD=50;
 private final JdbcTemplate db;
 public BacklogHealthIndicator(JdbcTemplate db){this.db=db;}
 @Override public Health health(){
  long deadJobs=db.queryForObject("SELECT count(*) FROM scoring_jobs WHERE state='DEAD'",Long.class);
  long failedSms=db.queryForObject("SELECT count(*) FROM sms_deliveries WHERE state='FAILED' AND created_at>now()-interval '1 hour'",Long.class);
  long failedEmail=db.queryForObject("SELECT count(*) FROM email_deliveries WHERE state='FAILED' AND created_at>now()-interval '1 hour'",Long.class);
  Health.Builder builder=(deadJobs>=DEAD_JOBS_THRESHOLD||failedSms>=FAILED_DELIVERIES_THRESHOLD||failedEmail>=FAILED_DELIVERIES_THRESHOLD)?Health.down():Health.up();
  return builder.withDetail("deadScoringJobs",deadJobs).withDetail("failedSmsLastHour",failedSms).withDetail("failedEmailLastHour",failedEmail).build();
 }
}
