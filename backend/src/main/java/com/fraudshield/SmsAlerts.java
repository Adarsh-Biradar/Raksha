package com.fraudshield;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// Hardcoded per explicit request for hackathon/testing speed, not environment config.
// Move HTTPSMS_API_KEY and HTTPSMS_FROM_NUMBER to env vars/secrets before any real deployment.
@Service
public class SmsAlerts {
 private static final String HTTPSMS_API_KEY="uk_YDFF4-aeTaFQiBb9IUx5O7d5T6fA87wxvpmo7AtfiCoA1hpB7BgEAPhtIwOrqn2o";
 private static final String HTTPSMS_FROM_NUMBER="+919652942332";
 private static final URI HTTPSMS_ENDPOINT=URI.create("https://api.httpsms.com/v1/messages/send");

 final JdbcTemplate db; final TransactionTemplate tx; final FraudService fraud; final ObjectMapper json;
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
 final Counter smsSent; final Counter smsFailed;

 public SmsAlerts(JdbcTemplate db,TransactionTemplate tx,FraudService fraud,ObjectMapper json,MeterRegistry meterRegistry){
  this.db=db;this.tx=tx;this.fraud=fraud;this.json=json;
  this.smsSent=Counter.builder("raksha.sms.sent").description("SMS messages successfully handed off to httpSMS").register(meterRegistry);
  this.smsFailed=Counter.builder("raksha.sms.failed").description("SMS send attempts that failed").register(meterRegistry);
  Gauge.builder("raksha.sms.pending",db,d->d.queryForObject("SELECT count(*) FROM sms_deliveries WHERE state='PENDING'",Long.class)).description("SMS deliveries awaiting send").register(meterRegistry);
 }

 // Called inside the payment-processing transaction: no network activity here.
 void enqueue(UUID transactionId,String outcome,String merchant,long amountMinor,String currency,String recipientPhone){
  if(recipientPhone==null||recipientPhone.isBlank())return;
  db.update("INSERT INTO sms_deliveries(id,transaction_id,recipient,outcome,merchant,amount_minor,currency) VALUES(?,?,?,?,?,?,?) ON CONFLICT(transaction_id,outcome) DO NOTHING",
   UUID.randomUUID(),transactionId,recipientPhone,outcome,merchant,amountMinor,currency);
 }

 @Scheduled(fixedDelay=2000)
 public void deliver(){
  for(int i=0;i<5;i++){
   try{
    Boolean found=tx.execute(status->{
     List<Map<String,Object>> rows=db.queryForList("SELECT * FROM sms_deliveries WHERE state='PENDING' AND available_at<=now() ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1");
     if(rows.isEmpty())return false;
     Map<String,Object> row=rows.get(0);Object id=row.get("id");
     try{
      String amount=String.format(Locale.ROOT,"%.2f",((Number)row.get("amount_minor")).longValue()/100.0);
      String date=java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy",Locale.ENGLISH).format(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
      String txnId=row.get("transaction_id").toString().substring(0,8);
      String text="Dear Customer, your payment of "+row.get("currency")+" "+amount+" to "+row.get("merchant")+" "+
       ("SUCCESS".equals(row.get("outcome"))?"was successful":"has failed")+" on "+date+". Txn ID: "+txnId+". -Raksha";
      String body=json.writeValueAsString(Map.of("from",HTTPSMS_FROM_NUMBER,"to",row.get("recipient"),"content",text));
      HttpRequest request=HttpRequest.newBuilder(HTTPSMS_ENDPOINT)
       .header("x-api-key",HTTPSMS_API_KEY).header("Content-Type","application/json")
       .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(10)).build();
      HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()/100!=2)throw new IllegalStateException("httpSMS returned "+response.statusCode()+": "+response.body());
      db.update("UPDATE sms_deliveries SET state='SENT',sent_at=now(),attempts=attempts+1,last_error=NULL WHERE id=?",id);
      fraud.audit("sms-worker","SMS_SENT",id,"outcome="+row.get("outcome"));
      smsSent.increment();
     }catch(Exception e){
      String reason=String.valueOf(e.getMessage());
      db.update("UPDATE sms_deliveries SET attempts=attempts+1,state=CASE WHEN attempts+1>=3 THEN 'FAILED' ELSE 'PENDING' END,available_at=now()+interval '30 seconds'*(attempts+1),last_error=? WHERE id=?",reason,id);
      fraud.audit("sms-worker","SMS_ATTEMPT_FAILED",id,reason);
      smsFailed.increment();
     }
     return true;
    });
    if(!Boolean.TRUE.equals(found))break;
   }catch(Exception databaseFailure){break;} // Committed queue remains durable on a database outage.
  }
 }
}
