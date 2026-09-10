package com.fraudshield;
import java.util.*;
import org.slf4j.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional-outbox relay: FraudService.ingest() commits an outbox_events row in the same
 * database transaction as the transaction/job insert. This poller (same FOR UPDATE SKIP LOCKED
 * pattern as RiskWorker) publishes unpublished rows to RabbitMQ and marks them published only
 * after a successful send. If the broker is unavailable, convertAndSend throws, the surrounding
 * transaction rolls back, and the row is retried on the next tick - no message is lost.
 */
@Component
@ConditionalOnProperty(name="app.worker-mode",havingValue="queue")
public class OutboxPublisher {
 private final JdbcTemplate db; private final TransactionTemplate tx; private final RabbitTemplate rabbit;
 private static final Logger log=LoggerFactory.getLogger(OutboxPublisher.class);
 public OutboxPublisher(JdbcTemplate db,TransactionTemplate tx,RabbitTemplate rabbit){this.db=db;this.tx=tx;this.rabbit=rabbit;}
 @Scheduled(fixedDelay=500)
 public void publish() {
  for(int i=0;i<25;i++) {
   Boolean sent;
   try {
    sent=tx.execute(status->{
     List<Map<String,Object>> rows=db.queryForList("""
      SELECT id,transaction_id,event_id FROM outbox_events
      WHERE published_at IS NULL ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
      """);
     if(rows.isEmpty()) return false;
     Map<String,Object> row=rows.get(0);
     rabbit.convertAndSend(RabbitConfig.EXCHANGE,RabbitConfig.TRANSACTIONS_QUEUE,
       Map.of("transactionId",row.get("transaction_id").toString(),"eventId",row.get("event_id")));
     db.update("UPDATE outbox_events SET published_at=now() WHERE id=?",row.get("id"));
     return true;
    });
   } catch(Exception e) {
    log.warn("Outbox publish failed, will retry: type={}",e.getClass().getSimpleName());
    break;
   }
   if(!Boolean.TRUE.equals(sent)) break;
  }
 }
}
