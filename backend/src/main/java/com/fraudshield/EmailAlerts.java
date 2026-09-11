package com.fraudshield;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailAlerts {
 final JdbcTemplate db; final TransactionTemplate tx; final FraudService fraud; final JavaMailSender sender;
 final String host,username,password,from; final int port;
 public EmailAlerts(JdbcTemplate db,TransactionTemplate tx,FraudService fraud,JavaMailSender sender,
 @Value("${spring.mail.host}") String host,@Value("${spring.mail.port}") int port,
 @Value("${spring.mail.username}") String username,@Value("${spring.mail.password}") String password,
 @Value("${app.mail-from}") String from){
  this.db=db;this.tx=tx;this.fraud=fraud;this.sender=sender;this.host=host;this.port=port;this.username=username;this.password=password;this.from=from;
 }
 boolean configured(){return !username.isBlank()&&!password.isBlank()&&!from.isBlank();}
 static List<String> recipients(Object value){return Arrays.stream(value.toString().split("\n")).filter(s->!s.isBlank()).toList();}
 // Called inside scoring's transaction: no SMTP/network activity here.
 void enqueue(UUID transactionId,String classification,int score,UUID orgId){
  if("NORMAL".equals(classification))return;
  Map<String,Object> settings=db.queryForMap("SELECT * FROM notification_settings WHERE org_id=? FOR SHARE",orgId);
  if(!Boolean.TRUE.equals(settings.get("enabled")))return;
  if("HIGH_RISK".equals(settings.get("minimum_classification"))&&!"HIGH_RISK".equals(classification))return;
  UUID alert=db.queryForObject("SELECT id FROM alerts WHERE transaction_id=?",UUID.class,transactionId);
  for(String recipient:recipients(settings.get("recipients")))
   db.update("INSERT INTO email_deliveries(id,alert_id,recipient,classification,score,transaction_id,org_id) VALUES(?,?,?,?,?,?,?) ON CONFLICT(alert_id,recipient) DO NOTHING",UUID.randomUUID(),alert,recipient,classification,score,transactionId,orgId);
 }
 @Scheduled(fixedDelay=2000)
 public void deliver(){
  // Bounded batch; scoring uses another scheduler thread.
  for(int i=0;i<5;i++){
   try{
    Boolean found=tx.execute(status->{
     List<Map<String,Object>> rows=db.queryForList("SELECT * FROM email_deliveries WHERE state='PENDING' AND available_at<=now() ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1");
     if(rows.isEmpty())return false;
     Map<String,Object> row=rows.get(0);Object id=row.get("id");
     Map<String,Object> settings=db.queryForMap("SELECT * FROM notification_settings WHERE org_id=? FOR SHARE",row.get("org_id"));
     boolean test="TEST".equals(row.get("classification"));
     boolean allowed=recipients(settings.get("recipients")).contains(row.get("recipient")) &&
      (test || (Boolean.TRUE.equals(settings.get("enabled")) && ("SUSPICIOUS".equals(settings.get("minimum_classification"))||"HIGH_RISK".equals(row.get("classification")))));
     if(!allowed){
      db.update("UPDATE email_deliveries SET state='CANCELLED',last_error='Recipient removed or notification policy changed' WHERE id=?",id);
      fraud.audit("mail-worker","EMAIL_CANCELLED",id,"Notification policy no longer permits delivery",(UUID)row.get("org_id"));return true;
     }
     try{
      if(!configured())throw new IllegalStateException("SMTP configuration missing");
      SimpleMailMessage message=new SimpleMailMessage();message.setFrom(from);message.setTo((String)row.get("recipient"));
      message.setSubject(test?"Raksha - email notification test":"Raksha - "+row.get("classification")+" alert");
      String body=test?"This is a test email from Raksha. Your management notification address is configured.":
       "A simulated transaction requires review.\n\nAlert ID: "+row.get("alert_id")+"\nTransaction ID: "+row.get("transaction_id")+"\nClassification: "+row.get("classification")+"\nRisk score: "+row.get("score")+"/100\n\nSign in to Raksha and open Investigations to review this alert.\nNo real funds have been moved or blocked.";
      message.setText(body+"\n\nNotification ID: "+id+"\nRaksha transaction intelligence");
      sender.send(message);
      db.update("UPDATE email_deliveries SET state='SENT',sent_at=now(),attempts=attempts+1,last_error=NULL WHERE id=?",id);
      fraud.audit("mail-worker","EMAIL_SENT",id,"SMTP server accepted the message",(UUID)row.get("org_id"));
     }catch(Exception e){
      String reason=e instanceof MailAuthenticationException?"SMTP authentication failed; check sender and app password":!configured()?"SMTP credentials are not configured":"SMTP delivery failed; check connectivity, TLS and recipient settings";
      db.update("UPDATE email_deliveries SET attempts=attempts+1,state=CASE WHEN attempts+1>=3 THEN 'FAILED' ELSE 'PENDING' END,available_at=now()+interval '30 seconds'*(attempts+1),last_error=? WHERE id=?",reason,id);
      fraud.audit("mail-worker","EMAIL_ATTEMPT_FAILED",id,reason,(UUID)row.get("org_id"));
     }
     return true;
    });
    if(!Boolean.TRUE.equals(found))break;
   }catch(Exception databaseFailure){break;} // Committed queue remains durable on a database outage.
  }
 }
}
