package com.fraudshield;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class EmailQueueCheck {
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  String password=new BufferedReader(new InputStreamReader(System.in)).readLine();
  String schema="mail_check_"+UUID.randomUUID().toString().replace("-","");
  String base="jdbc:postgresql://127.0.0.1:54329/fraudshield";
  JdbcTemplate root=new JdbcTemplate(new DriverManagerDataSource(base,"fraudshield",password));
  root.execute("CREATE SCHEMA "+schema);
  try{
   DriverManagerDataSource source=new DriverManagerDataSource(base+"?currentSchema="+schema,"fraudshield",password);
   JdbcTemplate db=new JdbcTemplate(source);TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(source));
   db.execute("CREATE TABLE notification_settings(id int primary key,enabled boolean,minimum_classification text,recipients text)");
   db.execute("INSERT INTO notification_settings VALUES(1,true,'HIGH_RISK','manager@example.invalid')");
   db.execute("CREATE TABLE alerts(id uuid primary key,transaction_id uuid unique)");
   db.execute("CREATE TABLE audit_events(actor text,action text,target text,details text)");
   db.execute("CREATE TABLE email_deliveries(id uuid primary key,alert_id uuid,recipient text,classification text,score int,transaction_id uuid,state text default 'PENDING',attempts int default 0,available_at timestamptz default now(),created_at timestamptz default now(),sent_at timestamptz,last_error text,unique(alert_id,recipient))");
   FraudService fraud=new FraudService(db,new ObjectMapper(),new SimpleMeterRegistry(),"poll");
   JavaMailSenderImpl failure=new JavaMailSenderImpl(){@Override public void send(SimpleMailMessage message){throw new MailAuthenticationException("test-only failure");}};
   EmailAlerts failing=new EmailAlerts(db,tx,fraud,failure,"smtp.invalid",587,"test","test-secret","sender@example.invalid");
   UUID failed=UUID.randomUUID();
   db.update("INSERT INTO email_deliveries(id,recipient,classification) VALUES(?,'manager@example.invalid','TEST')",failed);
   for(int i=1;i<=3;i++){db.update("UPDATE email_deliveries SET available_at=now() WHERE id=?",failed);failing.deliver();check(db.queryForObject("SELECT attempts FROM email_deliveries WHERE id=?",Integer.class,failed)==i,"Attempt count");}
   check("FAILED".equals(db.queryForObject("SELECT state FROM email_deliveries WHERE id=?",String.class,failed)),"Third failure becomes terminal");
   check(!db.queryForObject("SELECT last_error FROM email_deliveries WHERE id=?",String.class,failed).contains("test-secret"),"Secret not exposed");
   AtomicInteger sent=new AtomicInteger();
   JavaMailSenderImpl success=new JavaMailSenderImpl(){@Override public void send(SimpleMailMessage message){sent.incrementAndGet();check(message.getTo().length==1,"One recipient per message");}};
   EmailAlerts working=new EmailAlerts(db,tx,fraud,success,"smtp.invalid",587,"test","test-secret","sender@example.invalid");
   UUID txn=UUID.randomUUID(),alert=UUID.randomUUID();db.update("INSERT INTO alerts VALUES(?,?)",alert,txn);
   tx.executeWithoutResult(s->{working.enqueue(txn,"HIGH_RISK",100);working.enqueue(txn,"HIGH_RISK",100);});
   check(db.queryForObject("SELECT count(*) FROM email_deliveries WHERE alert_id=?",Integer.class,alert)==1,"Unique outbox entry");
   working.deliver();working.deliver();check(sent.get()==1,"No repeated SMTP send after SENT");
   UUID removed=UUID.randomUUID();db.update("INSERT INTO email_deliveries(id,recipient,classification) VALUES(?,'removed@example.invalid','TEST')",removed);
   working.deliver();check("CANCELLED".equals(db.queryForObject("SELECT state FROM email_deliveries WHERE id=?",String.class,removed)),"Removed recipient cancelled");
   UUID paused=UUID.randomUUID();db.update("INSERT INTO email_deliveries(id,recipient,classification) VALUES(?,'manager@example.invalid','HIGH_RISK')",paused);
   db.update("UPDATE notification_settings SET enabled=false");
   working.deliver();check("CANCELLED".equals(db.queryForObject("SELECT state FROM email_deliveries WHERE id=?",String.class,paused)),"Paused notification cancelled");
   check(sent.get()==1,"Cancellation never sends");
   db.update("UPDATE notification_settings SET enabled=true");
   db.update("UPDATE email_deliveries SET state='PENDING',attempts=0,available_at=now() WHERE id=?",failed);
   working.deliver();check(sent.get()==2,"Retried message sends successfully");
   System.out.println("PASS: isolated PostgreSQL queue checks - bounded retries, terminal failure, safe error, duplicate suppression, recipient removal, pause and retry recovery. No SMTP network calls.");
  } finally {root.execute("DROP SCHEMA "+schema+" CASCADE");}
 }
}
