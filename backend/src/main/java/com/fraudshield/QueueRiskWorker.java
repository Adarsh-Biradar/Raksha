package com.fraudshield;
import java.util.*;
import org.slf4j.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Event-driven consumer of transactions.ingested. Idempotent by construction: it only claims a
 * job that is still PENDING under FOR UPDATE SKIP LOCKED, so redelivery (at-least-once) or a
 * race with RiskWorker's DB poll never double-scores a transaction or creates a duplicate alert
 * (the alerts table also has a unique constraint on transaction_id).
 */
@Component
@ConditionalOnProperty(name="app.worker-mode",havingValue="queue")
public class QueueRiskWorker {
 private final JdbcTemplate db; private final TransactionTemplate tx; private final ScoringEngine scoringEngine;
 private final Timer scoringLatency;
 private static final Logger log=LoggerFactory.getLogger(QueueRiskWorker.class);
 public QueueRiskWorker(JdbcTemplate db,TransactionTemplate tx,ScoringEngine scoringEngine,MeterRegistry meterRegistry){
  this.db=db;this.tx=tx;this.scoringEngine=scoringEngine;
  this.scoringLatency=Timer.builder("raksha.scoring.latency").description("Time to score one transaction").register(meterRegistry);
 }
 @RabbitListener(queues=RabbitConfig.TRANSACTIONS_QUEUE,containerFactory="transactionsListenerContainerFactory")
 public void onMessage(Map<String,Object> payload) {
  UUID transactionId=UUID.fromString((String)payload.get("transactionId"));
  Boolean handled=tx.execute(status->{
   List<Map<String,Object>> jobs=db.queryForList(
     "SELECT id FROM scoring_jobs WHERE transaction_id=? AND state='PENDING' FOR UPDATE SKIP LOCKED",transactionId);
   if(jobs.isEmpty()) return false;
   UUID jobId=(UUID)jobs.get(0).get("id");
   scoringLatency.record(()->scoringEngine.score(transactionId));
   db.update("UPDATE scoring_jobs SET state='DONE',last_error=NULL WHERE id=?",jobId);
   return true;
  });
  if(!Boolean.TRUE.equals(handled)) log.debug("Skipping transaction {} - already scored or claimed by the DB-poll worker",transactionId);
 }
}
