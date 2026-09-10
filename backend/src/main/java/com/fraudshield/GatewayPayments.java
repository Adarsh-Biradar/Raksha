package com.fraudshield;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GatewayPayments {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final RazorpayGateway gateway;private final AccountHolds holds;private final FraudService fraud;private final ObjectMapper json;
 public GatewayPayments(JdbcTemplate db,TransactionTemplate tx,RazorpayGateway gateway,AccountHolds holds,FraudService fraud,ObjectMapper json){this.db=db;this.tx=tx;this.gateway=gateway;this.holds=holds;this.fraud=fraud;this.json=json;}
 private Map<String,Object> one(String sql,Object...args){return db.queryForList(sql,args).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment or transaction not found"));}
 private void eligible(Map<String,Object> t){
  holds.lock((String)t.get("account_id"));
  if(t.get("phone_number")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"This transaction has no mobile number. Create a new transaction with a mobile number before starting checkout.");
  if(!holds.active((String)t.get("account_id")).isEmpty()||"BLOCKED".equals(t.get("status"))||(t.get("score") instanceof Number score && score.intValue()>=100))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Account or transaction is blocked. Resolve its investigation and create a new transaction.");
  if(!"SCORED".equals(t.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Wait for successful risk assessment before checkout.");
 }
 public Map<String,Object> status(UUID id){
  var t=one("SELECT id,account_id,status,score,amount_minor,currency,merchant,phone_number FROM transactions WHERE id=?",id);
  var orders=db.queryForList("SELECT * FROM gateway_orders WHERE transaction_id=?",id);
  var result=new HashMap<String,Object>();result.put("transaction",t);result.put("order",orders.isEmpty()?Map.of():orders.get(0));
  result.put("attempts",db.queryForList("SELECT payment_id,status,updated_at FROM gateway_attempts WHERE transaction_id=? ORDER BY updated_at DESC",id));
  result.put("accountHeld",!holds.active((String)t.get("account_id")).isEmpty());return result;
 }
 public List<Map<String,Object>> list(){return db.queryForList("SELECT g.*,t.account_id,t.merchant,t.amount_minor,t.score,t.classification FROM gateway_orders g JOIN transactions t ON t.id=g.transaction_id ORDER BY g.created_at DESC LIMIT 100");}
 public Map<String,Object> checkout(UUID id,String actor){
  gateway.requireConfigured();
  // Commit the single creation intent BEFORE calling the remote provider. Never blindly POST again.
  boolean create=Boolean.TRUE.equals(tx.execute(s->{
   var t=one("SELECT * FROM transactions WHERE id=?",id);eligible(t);
   boolean inserted=db.update("INSERT INTO gateway_orders(transaction_id,receipt,status,created_by) VALUES(?,?,'CREATING',?) ON CONFLICT(transaction_id) DO NOTHING",id,id.toString(),actor)==1;
   if(inserted)fraud.audit(actor,"PAYMENT_ORDER_REQUESTED",id,"Sandbox order intent recorded");return inserted;
  }));
  if(create){
   var t=one("SELECT * FROM transactions WHERE id=?",id);
   try{
    var order=gateway.call("POST","orders",Map.of("amount",t.get("amount_minor"),"currency",t.get("currency"),"receipt",id.toString()));
    attach(id,order,actor);
   }catch(RuntimeException e){db.update("UPDATE gateway_orders SET status='UNKNOWN',updated_at=now() WHERE transaction_id=? AND status='CREATING'",id);throw e;}
  }
  return tx.execute(s->{
   var t=one("SELECT * FROM transactions WHERE id=?",id);eligible(t);
   var g=one("SELECT * FROM gateway_orders WHERE transaction_id=? FOR UPDATE",id);
   if(g.get("provider_order_id")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Order creation is unconfirmed. Use Reconcile before checkout; it will not create another order.");
   if(Set.of("CAPTURED","AUTHORIZED").contains(g.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment is already authorized or captured. Reconcile its status.");
   fraud.audit(actor,"PAYMENT_CHECKOUT_READY",id,"order="+g.get("provider_order_id"));
   return Map.of("key",gateway.key(),"orderId",g.get("provider_order_id"),"amount",t.get("amount_minor"),"currency",t.get("currency"),"merchant",t.get("merchant"),"contact",t.get("phone_number"));
  });
 }
 private void attach(UUID id,JsonNode order,String actor){
  tx.executeWithoutResult(s->{
   var g=one("SELECT * FROM gateway_orders WHERE transaction_id=? FOR UPDATE",id);
   var t=one("SELECT * FROM transactions WHERE id=?",id);
   if(!id.toString().equals(order.path("receipt").asText())||!order.path("id").asText().matches("order_[A-Za-z0-9]+")||order.path("amount").asLong(-1)!=((Number)t.get("amount_minor")).longValue()||!t.get("currency").equals(order.path("currency").asText()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Provider order does not match the stored transaction.");
   if(g.get("provider_order_id")!=null&&!g.get("provider_order_id").equals(order.path("id").asText()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Multiple orders require manual investigation.");
   db.update("UPDATE gateway_orders SET provider_order_id=?,status=CASE WHEN status IN ('CREATING','UNKNOWN') THEN 'CREATED' ELSE status END,updated_at=now() WHERE transaction_id=?",order.path("id").asText(),id);
   fraud.audit(actor,"PAYMENT_ORDER_LINKED",id,"order="+order.path("id").asText());
  });
 }
 public Map<String,Object> reconcile(UUID id,String actor){
  gateway.requireConfigured();var g=one("SELECT * FROM gateway_orders WHERE transaction_id=?",id);
  if(g.get("provider_order_id")==null){
   JsonNode items=gateway.call("GET","orders?receipt="+id+"&count=100",null).path("items");
   List<JsonNode> exact=new ArrayList<>();items.forEach(o->{if(id.toString().equals(o.path("receipt").asText()))exact.add(o);});
   if(exact.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"No unique provider order found. Check Razorpay Test Dashboard before taking further action; no replacement order was created.");
   attach(id,exact.get(0),actor);g=one("SELECT * FROM gateway_orders WHERE transaction_id=?",id);
  }
  JsonNode items=gateway.call("GET","orders/"+g.get("provider_order_id")+"/payments",null).path("items");
  for(JsonNode payment:items)apply(payment,actor);
  return status(id);
 }
 public Map<String,Object> confirm(UUID id,String payment,String signature,String actor){
  var g=one("SELECT * FROM gateway_orders WHERE transaction_id=?",id);
  if(g.get("provider_order_id")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Order is not linked yet");
  gateway.verifyCheckout((String)g.get("provider_order_id"),payment,signature);
  JsonNode p=gateway.call("GET","payments/"+payment,null);
  if(!g.get("provider_order_id").equals(p.path("order_id").asText())||!payment.equals(p.path("id").asText()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Payment does not belong to this order");
  apply(p,actor);return status(id);
 }
 // A payment attempt can fail and later authorize. Captured and authorized never regress.
 static String advance(String previous,String next){
  if("captured".equals(previous)||"captured".equals(next))return "captured";
  if("authorized".equals(previous)||"authorized".equals(next))return "authorized";
  if("failed".equals(previous)||"failed".equals(next))return "failed";return "created";
 }
 private boolean apply(JsonNode payment,String actor){return Boolean.TRUE.equals(tx.execute(s->applyLocked(payment,actor)));}
 private boolean applyLocked(JsonNode payment,String actor){
  var rows=db.queryForList("SELECT g.*,t.amount_minor,t.currency FROM gateway_orders g JOIN transactions t ON t.id=g.transaction_id WHERE provider_order_id=? FOR UPDATE OF g",payment.path("order_id").asText());
  if(rows.isEmpty())return false;var g=rows.get(0);
  String next=payment.path("status").asText(),pid=payment.path("id").asText();
  if(!pid.matches("pay_[A-Za-z0-9]+")||!Set.of("created","authorized","captured","failed").contains(next)||!payment.path("amount").isIntegralNumber()||payment.path("amount").asLong(-1)!=((Number)g.get("amount_minor")).longValue()||!g.get("currency").equals(payment.path("currency").asText()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provider payment does not match the stored amount, currency or payment format");
  var attempts=db.queryForList("SELECT * FROM gateway_attempts WHERE payment_id=?",pid);
  if(!attempts.isEmpty()&&!attempts.get(0).get("transaction_id").equals(g.get("transaction_id")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Payment already linked to a different order");
  String prior=attempts.isEmpty()?"created":(String)attempts.get(0).get("status");String state=advance(prior,next);
  db.update("INSERT INTO gateway_attempts(payment_id,transaction_id,status) VALUES(?,?,?) ON CONFLICT(payment_id) DO UPDATE SET status=EXCLUDED.status,updated_at=now()",pid,g.get("transaction_id"),state);
  String combined=advance(((String)g.get("status")).toLowerCase(),state).toUpperCase(Locale.ROOT);
  db.update("UPDATE gateway_orders SET status=?,updated_at=now() WHERE transaction_id=?",combined,g.get("transaction_id"));
  if(attempts.isEmpty()||!state.equals(prior))fraud.audit(actor,"PAYMENT_"+state.toUpperCase(Locale.ROOT),g.get("transaction_id"),"payment="+pid);
  return true;
 }
 public Map<String,Object> webhook(byte[] raw,String signature,String eventId){
  if(raw.length>131072||eventId==null||!eventId.matches("[A-Za-z0-9_-]{1,150}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid webhook envelope");
  gateway.verifyWebhook(raw,signature);
  try{
   JsonNode body=json.readTree(raw);String type=body.path("event").asText();
   if(!Set.of("payment.authorized","payment.captured","payment.failed").contains(type))return Map.of("ignored",true);
   JsonNode p=body.path("payload").path("payment").path("entity");
   if(!p.path("id").asText().matches("pay_[A-Za-z0-9]+")||!p.path("order_id").asText().matches("order_[A-Za-z0-9]+")||!type.equals("payment."+p.path("status").asText())||!p.path("amount").isIntegralNumber()||p.path("amount").asLong(-1)<1||!p.path("currency").asText().equals("INR"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid payment event");
   String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
   return tx.execute(s->{
    int inserted=db.update("INSERT INTO gateway_events(event_id,payload_hash,event_type,order_id,payment_id,amount_minor,currency,payment_status) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING",eventId,hash,type,p.path("order_id").asText(),p.path("id").asText(),p.path("amount").asLong(),p.path("currency").asText(),p.path("status").asText());
    if(!hash.equals(db.queryForObject("SELECT payload_hash FROM gateway_events WHERE event_id=?",String.class,eventId)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Webhook event ID reused with different content");
    return Map.of("accepted",true,"duplicate",inserted==0);
   });
  }catch(ResponseStatusException e){throw e;}catch(org.springframework.dao.DataAccessException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Webhook storage unavailable; retry delivery");}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid webhook payload");}
 }
 @Scheduled(fixedDelay=2000)
 public void processEvents(){
  for(int i=0;i<20;i++){
   boolean worked=Boolean.TRUE.equals(tx.execute(s->{
    var rows=db.queryForList("SELECT * FROM gateway_events WHERE state='PENDING' AND available_at<=now() ORDER BY received_at FOR UPDATE SKIP LOCKED LIMIT 1");if(rows.isEmpty())return false;
    var e=rows.get(0);boolean applied;
    try{applied=applyLocked(json.valueToTree(Map.of("id",e.get("payment_id"),"order_id",e.get("order_id"),"amount",e.get("amount_minor"),"currency",e.get("currency"),"status",e.get("payment_status"))),"razorpay-webhook");}
    catch(ResponseStatusException invalid){db.update("UPDATE gateway_events SET state='REJECTED' WHERE event_id=?",e.get("event_id"));fraud.audit("razorpay-webhook","PAYMENT_EVENT_REJECTED",e.get("event_id"),"Payment identity or amount mismatch");return true;}
    db.update("UPDATE gateway_events SET state=?,attempts=attempts+1,available_at=now()+interval '30 seconds' WHERE event_id=?",applied?"PROCESSED":((Number)e.get("attempts")).intValue()>=119?"IGNORED":"PENDING",e.get("event_id"));return true;
   }));if(!worked)break;
  }
 }
}



