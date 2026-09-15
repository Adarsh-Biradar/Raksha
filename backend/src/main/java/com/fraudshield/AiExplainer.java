package com.fraudshield;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// Turns the deterministic, already-explainable rule output from ScoringEngine into an analyst-facing
// narrative. The LLM is never the source of truth for the score/classification/reasons - it only
// rephrases them. Prompt input is limited to non-PII transaction signals (no phone/IP/account/device id).
@Service
public class AiExplainer {
 private static final Logger log=LoggerFactory.getLogger(AiExplainer.class);
 private static final String SYSTEM_PROMPT="""
  You are a fraud-analyst assistant. You will be given a transaction's risk score, classification and the \
  exact rule-based reasons that already produced that score. Write a short (2-4 sentence) plain-English \
  narrative for a human fraud analyst summarizing why this transaction was flagged. Only use facts present \
  in the input. Do not invent amounts, thresholds, locations or counts that are not given to you. Do not \
  give investigation instructions or recommend an outcome; only explain the signals.""";
 private final JdbcTemplate db; private final TransactionTemplate tx; private final AzureOpenAiClient ai;
 private final FraudService fraud; private final ObjectMapper json; private final MeterRegistry meterRegistry;
 public AiExplainer(JdbcTemplate db,TransactionTemplate tx,AzureOpenAiClient ai,FraudService fraud,ObjectMapper json,MeterRegistry meterRegistry){
  this.db=db;this.tx=tx;this.ai=ai;this.fraud=fraud;this.json=json;this.meterRegistry=meterRegistry;
 }
 void enqueue(UUID transactionId){
  db.update("INSERT INTO ai_explanations(id,transaction_id) VALUES(?,?) ON CONFLICT(transaction_id) DO NOTHING",UUID.randomUUID(),transactionId);
 }
 String prompt(Map<String,Object> t){
  return "score="+t.get("score")+" classification="+t.get("classification")+" currency="+t.get("currency")
   +" amountMinor="+t.get("amount_minor")+" reasons="+t.get("explanation")+" features="+t.get("features");
 }
 @Scheduled(fixedDelay=3000)
 public void deliver(){
  for(int i=0;i<5;i++){
   Boolean found=tx.execute(status->{
    List<Map<String,Object>> rows=db.queryForList("SELECT * FROM ai_explanations WHERE state='PENDING' AND available_at<=now() ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1");
    if(rows.isEmpty())return false;
    Map<String,Object> row=rows.get(0);Object id=row.get("id");
    if(!ai.configured()){
     db.update("UPDATE ai_explanations SET state='FAILED',last_error='AI not configured' WHERE id=?",id);
     return true;
    }
    try{
     Map<String,Object> t=db.queryForMap("SELECT score,classification,currency,amount_minor,explanation,features,org_id FROM transactions WHERE id=?",row.get("transaction_id"));
     long start=System.nanoTime();
     AzureOpenAiClient.Reply reply=ai.chat(SYSTEM_PROMPT,prompt(t));
     long latencyMs=(System.nanoTime()-start)/1_000_000;
     db.update("UPDATE ai_explanations SET state='SENT',sent_at=now(),attempts=attempts+1,last_error=NULL,narrative=?,model=?,prompt_tokens=?,completion_tokens=?,latency_ms=? WHERE id=?",
      reply.content(),"azure-openai",reply.promptTokens(),reply.completionTokens(),(int)latencyMs,id);
     fraud.audit("ai-explainer","AI_EXPLANATION_SENT",row.get("transaction_id"),"tokens="+(reply.promptTokens()+reply.completionTokens()),(UUID)t.get("org_id"));
     log.info("AI explanation generated transaction={} latencyMs={} tokens={}",row.get("transaction_id"),latencyMs,reply.promptTokens()+reply.completionTokens());
     meterRegistry.counter("raksha.ai.explanations","result","sent").increment();
     Timer.builder("raksha.ai.explanations.latency").register(meterRegistry).record(java.time.Duration.ofMillis(latencyMs));
    }catch(Exception e){
     String reason=String.valueOf(e.getMessage());
     db.update("UPDATE ai_explanations SET attempts=attempts+1,state=CASE WHEN attempts+1>=3 THEN 'FAILED' ELSE 'PENDING' END,available_at=now()+interval '30 seconds'*(attempts+1),last_error=? WHERE id=?",reason,id);
     log.warn("AI explanation attempt failed transaction={}",row.get("transaction_id"));
     meterRegistry.counter("raksha.ai.explanations","result","failed").increment();
    }
    return true;
   });
   if(!Boolean.TRUE.equals(found))break;
  }
 }
}
