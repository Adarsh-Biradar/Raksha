package com.fraudshield;
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.flywaydb.core.Flyway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class GatewayPaymentCheck {
 static final ObjectMapper json=new ObjectMapper();
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static void rejects(int code,Runnable r){try{r.run();throw new AssertionError("Expected HTTP "+code);}catch(ResponseStatusException e){check(e.getStatusCode().value()==code,e.toString());}}
 static String sign(String key,byte[] data)throws Exception{var m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(m.doFinal(data));}
 static class Fake extends RazorpayGateway {
  int posts;boolean timeout;JsonNode lastOrder,payment;Map<String,JsonNode> orders=new HashMap<>();
  Fake(){super(json,"rzp_test_local","test_secret","webhook_secret");}
  public JsonNode call(String method,String path,Object body){
   if(method.equals("POST")){posts++;var b=json.valueToTree(body);lastOrder=json.valueToTree(Map.of("id","order_Test"+posts,"receipt",b.path("receipt").asText(),"amount",b.path("amount").asLong(),"currency",b.path("currency").asText()));orders.put(lastOrder.path("receipt").asText(),lastOrder);if(timeout)throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"Synthetic timeout after provider commit");return lastOrder;}
   if(path.startsWith("orders?")){String id=path.substring("orders?receipt=".length()).split("&")[0];return json.valueToTree(Map.of("items",orders.containsKey(id)?List.of(orders.get(id)):List.of()));}
   if(path.endsWith("/payments"))return json.valueToTree(Map.of("items",payment==null?List.of():List.of(payment)));
   return payment;
  }
 }
 static UUID transaction(JdbcTemplate db,String status,int score){UUID id=UUID.randomUUID();db.update("INSERT INTO transactions(id,event_id,payload_hash,account_id,amount_minor,currency,merchant,country,device_id,failed_attempts,occurred_at,status,score,phone_number) VALUES(?,?,?, ?,10000,'INR','Gateway test','IN','device',0,now(),?,?,'+12025550123')",id,id.toString(),"test","account-"+id,status,score);return id;}
 static byte[] event(String order,String payment,String status,long amount)throws Exception{return json.writeValueAsBytes(Map.of("event","payment."+status,"payload",Map.of("payment",Map.of("entity",Map.of("id",payment,"order_id",order,"status",status,"amount",amount,"currency","INR")))));}
 public static void main(String[] args)throws Exception{
  String password=System.getenv("DATABASE_PASSWORD");check(password!=null,"DATABASE_PASSWORD required");
  String schema="gateway_test_"+UUID.randomUUID().toString().replace("-","");
  var root=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://localhost:54329/fraudshield","fraudshield",password));root.execute("CREATE SCHEMA "+schema);
  try{
   var source=new DriverManagerDataSource("jdbc:postgresql://localhost:54329/fraudshield?currentSchema="+schema,"fraudshield",password);
   Flyway.configure().dataSource(source).locations("filesystem:backend/src/main/resources/db/migration").load().migrate();
   var db=new JdbcTemplate(source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));var holds=new AccountHolds(db);var fake=new Fake();var fraud=new FraudService(db,json,new SimpleMeterRegistry(),"poll",holds);var service=new GatewayPayments(db,tx,fake,holds,fraud,json);
   check(!new RazorpayGateway(json,"rzp_live_bad","secret","webhook").configured(),"Live keys must be disabled");
   rejects(503,()->new RazorpayGateway(json,"","","").requireConfigured());
   rejects(409,()->service.checkout(transaction(db,"PENDING",0),"tester"));rejects(409,()->service.checkout(transaction(db,"SCORED",100),"tester"));
   UUID held=transaction(db,"SCORED",0),alert=UUID.randomUUID();db.update("INSERT INTO alerts(id,transaction_id) VALUES(?,?)",alert,held);db.update("INSERT INTO account_holds(alert_id,account_id) VALUES(?,?)",alert,"account-"+held);rejects(409,()->service.checkout(held,"tester"));check(fake.posts==0,"Risk gate before provider call");
   UUID id=transaction(db,"SCORED",25);var order=service.checkout(id,"tester");check("+12025550123".equals(order.get("contact")),"Checkout uses stored phone");String oid=(String)order.get("orderId");service.checkout(id,"tester");check(fake.posts==1,"Retry reuses provider order");
   byte[] captured=event(oid,"pay_Test1","captured",10000);String sig=sign("webhook_secret",captured);
   rejects(400,()->service.webhook(captured,"0".repeat(64),"evt_bad"));check(db.queryForObject("SELECT count(*) FROM gateway_events",Integer.class)==0,"Invalid signatures never persisted");
   service.webhook(captured,sig,"evt_1");check(Boolean.TRUE.equals(service.webhook(captured,sig,"evt_1").get("duplicate")),"Duplicate acknowledged");
   byte[] failed=event(oid,"pay_Test1","failed",10000);String fs=sign("webhook_secret",failed);rejects(409,()->service.webhook(failed,fs,"evt_1"));
   service.processEvents();service.webhook(failed,fs,"evt_late");service.processEvents();check("CAPTURED".equals(((Map<?,?>)service.status(id).get("order")).get("status")),"Captured cannot regress");
   check(db.queryForObject("SELECT count(*) FROM audit_events WHERE action='PAYMENT_CAPTURED'",Integer.class)==1,"No duplicate business effect");
   byte[] wrong=event(oid,"pay_Wrong","captured",9999);service.webhook(wrong,sign("webhook_secret",wrong),"evt_wrong");service.processEvents();check("REJECTED".equals(db.queryForObject("SELECT state FROM gateway_events WHERE event_id='evt_wrong'",String.class)),"Mismatched amount rejected durably");
   UUID unknown=transaction(db,"SCORED",0);fake.timeout=true;rejects(502,()->service.checkout(unknown,"tester"));fake.timeout=false;int posts=fake.posts;rejects(409,()->service.checkout(unknown,"tester"));check(posts==fake.posts,"Unknown order never blindly recreated");service.reconcile(unknown,"tester");check("CREATED".equals(((Map<?,?>)service.status(unknown).get("order")).get("status")),"Receipt recovery links order");
   String uoid=fake.lastOrder.path("id").asText();fake.payment=json.valueToTree(Map.of("id","pay_Confirm","order_id",uoid,"status","authorized","amount",10000,"currency","INR"));
   rejects(400,()->service.confirm(unknown,"pay_Confirm","0".repeat(64),"tester"));
   service.confirm(unknown,"pay_Confirm",sign("test_secret",(uoid+"|pay_Confirm").getBytes(StandardCharsets.UTF_8)),"tester");check("AUTHORIZED".equals(((Map<?,?>)service.status(unknown).get("order")).get("status")),"Authorization not capture");rejects(409,()->service.checkout(unknown,"tester"));
   fake.payment=json.valueToTree(Map.of("id","pay_Confirm","order_id",uoid,"status","captured","amount",10000,"currency","INR"));service.reconcile(unknown,"tester");check("CAPTURED".equals(((Map<?,?>)service.status(unknown).get("order")).get("status")),"Reconcile captures authoritative state");
   UUID concurrent=transaction(db,"SCORED",0);var pool=java.util.concurrent.Executors.newFixedThreadPool(4);int initialPosts=fake.posts;
   try{var work=new ArrayList<java.util.concurrent.Future<?>>();for(int i=0;i<4;i++)work.add(pool.submit(()->{try{service.checkout(concurrent,"tester");}catch(ResponseStatusException e){check(e.getStatusCode().value()==409,"Only creation-in-progress conflict allowed");}}));for(var f:work)f.get();}finally{pool.shutdown();}check(fake.posts==initialPosts+1,"Concurrent checkout creates one order");
   check(db.queryForObject("SELECT count(*) FROM email_deliveries",Integer.class)==0,"Payment events do not duplicate fraud notifications");
   System.out.println("PASS: isolated PostgreSQL migration, test-only keys, risk/hold gates, signatures, webhook deduplication, mismatched amounts, out-of-order events, checkout verification, unknown-order recovery, reconciliation and concurrent order creation.");
  }finally{root.execute("DROP SCHEMA "+schema+" CASCADE");}
 }
}

