package com.fraudshield;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SmsAlerts {
 private static final Logger log=LoggerFactory.getLogger(SmsAlerts.class);
 private static final URI HTTPSMS_ENDPOINT=URI.create("https://api.httpsms.com/v1/messages/send");
 private final String apiKey,fromNumber;

 final JdbcTemplate db; final TransactionTemplate tx; final FraudService fraud; final ObjectMapper json; final MeterRegistry meterRegistry;
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

 public SmsAlerts(JdbcTemplate db,TransactionTemplate tx,FraudService fraud,ObjectMapper json,MeterRegistry meterRegistry,
  @Value("${HTTPSMS_API_KEY:}") String apiKey,@Value("${HTTPSMS_FROM_NUMBER:}") String fromNumber){
  this.db=db;this.tx=tx;this.fraud=fraud;this.json=json;this.meterRegistry=meterRegistry;this.apiKey=apiKey;this.fromNumber=fromNumber;
 }
 private void smsEvent(String result){Counter.builder("raksha.sms.delivery").tag("result",result).register(meterRegistry).increment();}
 boolean configured(){return !apiKey.isBlank()&&!fromNumber.isBlank();}

 // Called inside the payment-processing transaction: no network activity here.
 void enqueue(UUID transactionId,String outcome,String merchant,long amountMinor,String currency,String recipientPhone){
  if(recipientPhone==null||recipientPhone.isBlank())return;
  db.update("""
   INSERT INTO sms_deliveries(id,transaction_id,recipient,outcome,merchant,amount_minor,currency,org_id)
   SELECT ?,?,?,?,?,?,?,org_id FROM transactions WHERE id=? ON CONFLICT(transaction_id,outcome) DO NOTHING
   """,UUID.randomUUID(),transactionId,recipientPhone,outcome,merchant,amountMinor,currency,transactionId);
 }

 @Scheduled(fixedDelay=2000)
 public void deliver(){
  for(int i=0;i<5;i++){
   try{
    Boolean found=tx.execute(status->{
     List<Map<String,Object>> rows=db.queryForList("SELECT * FROM sms_deliveries WHERE state='PENDING' AND available_at<=now() ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1");
     if(rows.isEmpty())return false;
     Map<String,Object> row=rows.get(0);Object id=row.get("id");
     if(!configured()){
      db.update("UPDATE sms_deliveries SET state='FAILED',last_error='SMS not configured' WHERE id=?",id);
      return true;
     }
     try{
      String amount=String.format(Locale.ROOT,"%.2f",((Number)row.get("amount_minor")).longValue()/100.0);
      String text="Raksha alert: transaction for "+row.get("merchant")+" ("+amount+" "+row.get("currency")+") "+
       ("SUCCESS".equals(row.get("outcome"))?"was SUCCESSFUL.":"has FAILED.")+" Ref: "+row.get("transaction_id");
      String body=json.writeValueAsString(Map.of("from",fromNumber,"to",row.get("recipient"),"content",text));
      HttpRequest request=HttpRequest.newBuilder(HTTPSMS_ENDPOINT)
       .header("x-api-key",apiKey).header("Content-Type","application/json")
       .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(10)).build();
      HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()/100!=2)throw new IllegalStateException("httpSMS returned "+response.statusCode()+": "+response.body());
      db.update("UPDATE sms_deliveries SET state='SENT',sent_at=now(),attempts=attempts+1,last_error=NULL WHERE id=?",id);
      fraud.audit("sms-worker","SMS_SENT",id,"outcome="+row.get("outcome"),(UUID)row.get("org_id"));
      log.info("SMS sent delivery={} transaction={} outcome={}",id,row.get("transaction_id"),row.get("outcome"));
      smsEvent("sent");
     }catch(Exception e){
      String reason=String.valueOf(e.getMessage());
      db.update("UPDATE sms_deliveries SET attempts=attempts+1,state=CASE WHEN attempts+1>=3 THEN 'FAILED' ELSE 'PENDING' END,available_at=now()+interval '30 seconds'*(attempts+1),last_error=? WHERE id=?",reason,id);
      fraud.audit("sms-worker","SMS_ATTEMPT_FAILED",id,reason,(UUID)row.get("org_id"));
      log.warn("SMS delivery attempt failed delivery={} transaction={}",id,row.get("transaction_id"));
      smsEvent("failed");
     }
     return true;
    });
    if(!Boolean.TRUE.equals(found))break;
   }catch(Exception databaseFailure){break;} // Committed queue remains durable on a database outage.
  }
 }
}
