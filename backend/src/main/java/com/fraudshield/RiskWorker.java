package com.fraudshield;
import java.util.*;
import org.slf4j.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable-queue poller (source of truth: the scoring_jobs table). Always active when the worker
 * is enabled, independent of app.worker-mode: it is the safety net that keeps processing jobs
 * even if RabbitMQ (see QueueRiskWorker) is unavailable or disabled. Row-level locking
 * (FOR UPDATE SKIP LOCKED) means it is safe to run this concurrently with QueueRiskWorker.
 */
@Component
@ConditionalOnProperty(name="app.worker-enabled",havingValue="true",matchIfMissing=true)
public class RiskWorker {
 private final JdbcTemplate db; private final TransactionTemplate tx; private final ScoringEngine scoringEngine;
 private static final Logger log=LoggerFactory.getLogger(RiskWorker.class);
 private final MeterRegistry meterRegistry;
 private final Timer scoringLatency; private final Counter jobsRetried; private final Counter jobsDeadLettered;
 public RiskWorker(JdbcTemplate db,TransactionTemplate tx,ScoringEngine scoringEngine,MeterRegistry meterRegistry){
  this.db=db;this.tx=tx;this.scoringEngine=scoringEngine;this.meterRegistry=meterRegistry;
  this.scoringLatency=Timer.builder("raksha.scoring.latency").description("Time to score one transaction").register(meterRegistry);
  this.jobsRetried=Counter.builder("raksha.jobs.retried").description("Scoring jobs retried after failure").register(meterRegistry);
  this.jobsDeadLettered=Counter.builder("raksha.jobs.dead_lettered").description("Scoring jobs marked DEAD after exhausting retries").register(meterRegistry);
  Gauge.builder("raksha.jobs.pending",db,d->d.queryForObject("SELECT count(*) FROM scoring_jobs WHERE state='PENDING'",Long.class)).description("Scoring jobs awaiting processing").register(meterRegistry);
  Gauge.builder("raksha.jobs.dead",db,d->d.queryForObject("SELECT count(*) FROM scoring_jobs WHERE state='DEAD'",Long.class)).description("Scoring jobs that exhausted retries").register(meterRegistry);
 }
 @Scheduled(fixedDelay=750)
 public void poll() {
  for(int i=0;i<25;i++) {
   UUID[] active={null};
   try {
    Boolean done=tx.execute(status->{
     List<Map<String,Object>> jobs=db.queryForList("""
      SELECT j.id,j.transaction_id FROM scoring_jobs j JOIN transactions t ON t.id=j.transaction_id
      WHERE j.state='PENDING' AND j.available_at<=now()
      ORDER BY t.occurred_at,t.received_at FOR UPDATE OF j SKIP LOCKED LIMIT 1
      """);
     if(jobs.isEmpty()) return false;
     active[0]=(UUID)jobs.get(0).get("id");
     scoringLatency.record(()->scoringEngine.score((UUID)jobs.get(0).get("transaction_id")));
     db.update("UPDATE scoring_jobs SET state='DONE',last_error=NULL WHERE id=?",active[0]);
     return true;
    });
    if(!Boolean.TRUE.equals(done)) break;
   } catch(Exception e) {
    log.error("Scoring failed job={} type={}",active[0],e.getClass().getSimpleName());
    meterRegistry.counter("raksha.scoring.exceptions","type",e.getClass().getSimpleName()).increment();
    if(active[0]!=null) try {
     tx.executeWithoutResult(s->{
      db.update("""
       UPDATE scoring_jobs SET attempts=attempts+1,
       state=CASE WHEN attempts+1>=3 THEN 'DEAD' ELSE 'PENDING' END,
       available_at=now()+interval '5 seconds'*(attempts+1),last_error='Scoring failed; inspect server logs'
       WHERE id=? AND state='PENDING'
       """,active[0]);
      db.update("UPDATE transactions SET status='FAILED' WHERE id IN (SELECT transaction_id FROM scoring_jobs WHERE id=? AND state='DEAD')",active[0]);
     });
     String finalState=db.queryForObject("SELECT state FROM scoring_jobs WHERE id=?",String.class,active[0]);
     if("DEAD".equals(finalState)) jobsDeadLettered.increment(); else jobsRetried.increment();
    } catch(Exception unavailable) {log.warn("Database unavailable; durable job remains pending");}
    break;
   }
  }
 }
}
