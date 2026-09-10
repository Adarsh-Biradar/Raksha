package com.fraudshield;
import java.util.*;
import org.slf4j.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles messages that exhausted QueueRiskWorker's bounded retry (see RabbitConfig's
 * RetryOperationsInterceptor + RepublishMessageRecoverer). Mirrors RiskWorker's DB-poll
 * dead-letter behavior: mark the job DEAD and the transaction FAILED. An admin can retry a dead
 * job from Rules & settings exactly as with poll-mode failures (retry re-queues via the
 * scoring_jobs table, which both RiskWorker and QueueRiskWorker read from).
 */
@Component
@ConditionalOnProperty(name="app.worker-mode",havingValue="queue")
public class TransactionsDeadLetterConsumer {
 private final JdbcTemplate db; private final TransactionTemplate tx; private final MeterRegistry meterRegistry;
 private static final Logger log=LoggerFactory.getLogger(TransactionsDeadLetterConsumer.class);
 public TransactionsDeadLetterConsumer(JdbcTemplate db,TransactionTemplate tx,MeterRegistry meterRegistry){
  this.db=db;this.tx=tx;this.meterRegistry=meterRegistry;
 }
 @RabbitListener(queues=RabbitConfig.TRANSACTIONS_DLQ)
 public void onDead(Map<String,Object> payload) {
  UUID transactionId=UUID.fromString((String)payload.get("transactionId"));
  tx.executeWithoutResult(status->{
   db.update("""
    UPDATE scoring_jobs SET state='DEAD',attempts=attempts+1,
    last_error='Scoring failed after retries; inspect server logs' WHERE transaction_id=? AND state='PENDING'
    """,transactionId);
   db.update("UPDATE transactions SET status='FAILED' WHERE id IN (SELECT transaction_id FROM scoring_jobs WHERE transaction_id=? AND state='DEAD')",transactionId);
  });
  meterRegistry.counter("raksha.jobs.dead_lettered").increment();
  log.error("transaction_dead_lettered id={}",transactionId);
 }
}
