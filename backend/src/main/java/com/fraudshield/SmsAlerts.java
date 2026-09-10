package com.fraudshield;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// Hardcoded per explicit request for hackathon/testing speed, not environment config.
// Move HTTPSMS_API_KEY and HTTPSMS_FROM_NUMBER to env vars/secrets before any real deployment.
@Service
public class SmsAlerts {
 private static final String HTTPSMS_API_KEY="pk_6_RhpNtDRAm3mLnYrV_1qwhwxdjSERzRblHswzByRAdOe8N_hpfvu5t8WPxIfVw3";
 // TODO: set this to the phone number registered in your httpSMS Android app (E.164, e.g. +15555550100).
 private static final String HTTPSMS_FROM_NUMBER="+00000000000";
 private static final URI HTTPSMS_ENDPOINT=URI.create("https://api.httpsms.com/v1/messages/send");

 final JdbcTemplate db; final TransactionTemplate tx; final FraudService fraud; final ObjectMapper json;
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

 public SmsAlerts(JdbcTemplate db,TransactionTemplate tx,FraudService fraud,ObjectMapper json){
  this.db=db;this.tx=tx;this.fraud=fraud;this.json=json;
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
      String text="Raksha alert: transaction for "+row.get("merchant")+" ("+amount+" "+row.get("currency")+") "+
       ("SUCCESS".equals(row.get("outcome"))?"was SUCCESSFUL.":"has FAILED.")+" Ref: "+row.get("transaction_id");
      String body=json.writeValueAsString(Map.of("from",HTTPSMS_FROM_NUMBER,"to",row.get("recipient"),"content",text));
      HttpRequest request=HttpRequest.newBuilder(HTTPSMS_ENDPOINT)
       .header("x-api-key",HTTPSMS_API_KEY).header("Content-Type","application/json")
       .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(10)).build();
      HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()/100!=2)throw new IllegalStateException("httpSMS returned "+response.statusCode()+": "+response.body());
      db.update("UPDATE sms_deliveries SET state='SENT',sent_at=now(),attempts=attempts+1,last_error=NULL WHERE id=?",id);
      fraud.audit("sms-worker","SMS_SENT",id,"outcome="+row.get("outcome"));
     }catch(Exception e){
      String reason=String.valueOf(e.getMessage());
      db.update("UPDATE sms_deliveries SET attempts=attempts+1,state=CASE WHEN attempts+1>=3 THEN 'FAILED' ELSE 'PENDING' END,available_at=now()+interval '30 seconds'*(attempts+1),last_error=? WHERE id=?",reason,id);
      fraud.audit("sms-worker","SMS_ATTEMPT_FAILED",id,reason);
     }
     return true;
    });
    if(!Boolean.TRUE.equals(found))break;
   }catch(Exception databaseFailure){break;} // Committed queue remains durable on a database outage.
  }
 }
}
