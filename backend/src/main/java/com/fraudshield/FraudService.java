package com.fraudshield;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FraudService {
 final JdbcTemplate db; final ObjectMapper json; final Counter transactionsIngested; final boolean queueMode; final AccountHolds holds;
 public FraudService(JdbcTemplate db,ObjectMapper json,MeterRegistry meterRegistry,@Value("${app.worker-mode:poll}") String workerMode,AccountHolds holds) {
  this.db=db;this.json=json;this.queueMode="queue".equals(workerMode);this.holds=holds;
  this.transactionsIngested=Counter.builder("raksha.transactions.ingested").description("Transactions accepted for scoring").register(meterRegistry);
 }
 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
 public record Input(
  @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String eventId,
  @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String accountId,
  @Min(1) @Max(100000000000L) long amountMinor,
  @NotNull @Pattern(regexp="INR") String currency,
  @NotBlank @Size(max=100) String merchant,
  @NotNull @Pattern(regexp="[A-Z]{2}") String country,
  @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String deviceId,
  @Min(0) @Max(100) int failedAttempts,
  @NotNull Instant occurredAt, @Size(max=45) String ipAddress, @NotBlank(message="Mobile number with country code is required") @Size(max=40) String phoneNumber) {
  public Input { ipAddress=SignalValues.ip(ipAddress); phoneNumber=SignalValues.phone(phoneNumber); }
  public Input(String eventId,String accountId,long amountMinor,String currency,String merchant,String country,String deviceId,int failedAttempts,Instant occurredAt){this(eventId,accountId,amountMinor,currency,merchant,country,deviceId,failedAttempts,occurredAt,null,null);}
 }
 String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 // No org attributable (e.g. a webhook event rejected before it matches any stored order): the row
 // stays invisible to every organization's audit view rather than being shown to the wrong one.
 void audit(String actor,String action,Object target,String details) {
  db.update("INSERT INTO audit_events(actor,action,target,details) VALUES(?,?,?,?)",actor,action,target.toString(),details);
 }
 void audit(String actor,String action,Object target,String details,UUID orgId) {
  db.update("INSERT INTO audit_events(actor,action,target,details,org_id) VALUES(?,?,?,?,?)",actor,action,target.toString(),details,orgId);
 }
 @Transactional
 public Map<String,Object> ingest(Input input,String key,String actor,UUID orgId) {
  if(!input.eventId().equals(key)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key must equal eventId");
  if(input.occurredAt().isAfter(Instant.now().plusSeconds(300)) || input.occurredAt().isBefore(Instant.now().minus(Duration.ofDays(365))))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Event time must be within the past year and no more than five minutes ahead");
  String hash;
  try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(input).getBytes(StandardCharsets.UTF_8)));}
  catch(Exception e){throw new IllegalStateException(e);}
  holds.lock(input.accountId()); UUID id=UUID.randomUUID();
  int inserted=db.update("""
   INSERT INTO transactions(id,event_id,payload_hash,account_id,amount_minor,currency,merchant,country,device_id,failed_attempts,occurred_at,late_event,ip_address,phone_number,org_id)
   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING
   """,id,input.eventId(),hash,input.accountId(),input.amountMinor(),input.currency(),input.merchant(),input.country(),input.deviceId(),input.failedAttempts(),Timestamp.from(input.occurredAt()),input.occurredAt().isBefore(Instant.now().minusSeconds(600)),input.ipAddress(),input.phoneNumber(),orgId);
  Map<String,Object> row=db.queryForMap("SELECT id,status,payload_hash FROM transactions WHERE event_id=?",input.eventId());
  if(!hash.equals(row.get("payload_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Event ID already used with a different payload");
  if(inserted==1) {
   if(!holds.block(id,input.accountId(),actor,this,orgId)) {
    db.update("INSERT INTO scoring_jobs(id,transaction_id,org_id) VALUES(?,?,?)",UUID.randomUUID(),id,orgId);
    if(queueMode) db.update("INSERT INTO outbox_events(id,transaction_id,event_id) VALUES(?,?,?)",UUID.randomUUID(),id,input.eventId());
   }
   enforceUsageCap(orgId);
   audit(actor,"TRANSACTION_ACCEPTED",id,"event="+input.eventId(),orgId);
   transactionsIngested.increment();
   row=db.queryForMap("SELECT id,status,payload_hash FROM transactions WHERE id=?",id);
  }
  return Map.of("id",row.get("id"),"status",row.get("status"),"duplicate",inserted==0);
 }
 // Usage-based billing: throwing here rolls back the whole ingest (transaction row included),
 // so a cap breach never leaves a half-accepted transaction behind.
 private void enforceUsageCap(UUID orgId) {
  String period=YearMonth.now().toString();
  long used=db.queryForObject("""
   INSERT INTO usage_counters(org_id,period,transactions_count) VALUES(?,?,1)
   ON CONFLICT(org_id,period) DO UPDATE SET transactions_count=usage_counters.transactions_count+1
   RETURNING transactions_count
   """,Long.class,orgId,period);
  Long cap=db.queryForObject("SELECT monthly_transaction_cap FROM organizations WHERE id=?",Long.class,orgId);
  if(cap!=null&&used>cap)
   throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,"Monthly transaction cap of "+cap+" reached for this organization's plan. Upgrade your plan to continue.");
 }
 @Transactional
 public Map<String,Object> simulate(String scenario,String actor,UUID orgId) {
  if(!Set.of("NORMAL","TAKEOVER","VELOCITY","LATE").contains(scenario)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown scenario");
  String account="demo-"+UUID.randomUUID().toString().substring(0,8);
  Instant now=Instant.now();
  List<Object> ids=new ArrayList<>();
  for(int i=0;i<8;i++) {
   String event="sim-"+UUID.randomUUID();
   ids.add(ingest(new Input(event,account,85000+i*6000,"INR","Everyday Market","IN","trusted-device",0,now.minus(Duration.ofDays(8-i))),event,actor,orgId).get("id"));
  }
  int count=scenario.equals("VELOCITY")?8:1;
  for(int i=0;i<count;i++) {
   String event="sim-"+UUID.randomUUID();
   boolean attack=scenario.equals("TAKEOVER");
   Instant time=scenario.equals("LATE")?now.minus(Duration.ofHours(2)):now.minusSeconds(count-i);
   ids.add(ingest(new Input(event,account,attack?8500000:scenario.equals("VELOCITY")?1800000:125000,"INR",
    attack?"Digital Asset Outlet":"Everyday Market",attack?"US":"IN",attack?"unknown-device":"trusted-device",attack?5:0,time),event,actor,orgId).get("id"));
  }
  audit(actor,"SIMULATION_CREATED",account,scenario,orgId);
  return Map.of("accountId",account,"transactionIds",ids,"scenario",scenario);
 }
}


