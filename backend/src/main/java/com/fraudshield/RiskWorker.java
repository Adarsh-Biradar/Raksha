package com.fraudshield;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name="app.worker-enabled",havingValue="true",matchIfMissing=true)
public class RiskWorker {
 private final JdbcTemplate db; private final TransactionTemplate tx; private final FraudService service; private final EmailAlerts email; private final RuleLab lab; private final AccountHolds holds;
 private static final Logger log=LoggerFactory.getLogger(RiskWorker.class);
 public RiskWorker(JdbcTemplate db,TransactionTemplate tx,FraudService service,EmailAlerts email,RuleLab lab,AccountHolds holds){this.db=db;this.tx=tx;this.service=service;this.email=email;this.lab=lab;this.holds=holds;}
 @Scheduled(fixedDelay=750)
 public void poll() {
  for(int i=0;i<25;i++) {
   UUID[] active={null};
   try {
    Boolean done=tx.execute(status->{
     List<Map<String,Object>> jobs=db.queryForList("""
      SELECT j.id,j.transaction_id FROM scoring_jobs j JOIN transactions t ON t.id=j.transaction_id
      WHERE j.state='PENDING' AND j.available_at<=now()
      ORDER BY t.occurred_at,t.received_at FOR UPDATE OF j SKIP LOCKED LIMIT 1
      """);
     if(jobs.isEmpty()) return false;
     active[0]=(UUID)jobs.get(0).get("id");
     score((UUID)jobs.get(0).get("transaction_id"));
     db.update("UPDATE scoring_jobs SET state='DONE',last_error=NULL WHERE id=?",active[0]);
     return true;
    });
    if(!Boolean.TRUE.equals(done)) break;
   } catch(Exception e) {
    log.error("Scoring failed job={} type={}",active[0],e.getClass().getSimpleName());
    if(active[0]!=null) try {
     tx.executeWithoutResult(s->{
      db.update("""
       UPDATE scoring_jobs SET attempts=attempts+1,
       state=CASE WHEN attempts+1>=3 THEN 'DEAD' ELSE 'PENDING' END,
       available_at=now()+interval '5 seconds'*(attempts+1),last_error='Scoring failed; inspect server logs'
       WHERE id=? AND state='PENDING'
       """,active[0]);
      db.update("UPDATE transactions SET status='FAILED' WHERE id IN (SELECT transaction_id FROM scoring_jobs WHERE id=? AND state='DEAD')",active[0]);
     });
    } catch(Exception unavailable) {log.warn("Database unavailable; durable job remains pending");}
    break;
   }
  }
 }
 private void score(UUID id) {
  Map<String,Object> t=db.queryForMap("SELECT * FROM transactions WHERE id=?",id);
  String account=(String)t.get("account_id");
  db.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",account);
  if("SCORED".equals(t.get("status"))||"BLOCKED".equals(t.get("status"))) return;
  if(holds.block(id,account,"risk-worker",service)) return;
  Timestamp event=(Timestamp)t.get("occurred_at");
  // Only previously received events strictly before this event contribute to its baseline.
  Map<String,Object> history=db.queryForMap("""
   SELECT count(*) n,coalesce(avg(amount_minor),0) average,coalesce(stddev_pop(amount_minor),0) deviation,
   count(*) FILTER(WHERE device_id=?) known_device,
   count(*) FILTER(WHERE country=?) known_country,
   count(*) FILTER(WHERE occurred_at>=?::timestamptz-interval '5 minutes') recent
   FROM transactions WHERE status<>'BLOCKED' AND account_id=? AND occurred_at<? AND received_at<=? AND currency=?
   """,t.get("device_id"),t.get("country"),event,account,event,t.get("received_at"),t.get("currency"));
  Map<String,Object> p=db.queryForMap("SELECT * FROM risk_policy WHERE id=1 FOR SHARE");
  long n=((Number)history.get("n")).longValue();
  double average=((Number)history.get("average")).doubleValue();
  double deviation=((Number)history.get("deviation")).doubleValue();
  long amount=((Number)t.get("amount_minor")).longValue();
  List<Map<String,Object>> rules=db.queryForList("SELECT * FROM detection_rules ORDER BY code");
  Map<String,Map<String,Object>> catalog=new HashMap<>(); for(Map<String,Object> rule:rules)catalog.put((String)rule.get("code"),rule);
  List<String> matched=new ArrayList<>();
  int points=0;
  List<String> reasons=new ArrayList<>();
  if(enabled(catalog,"LARGE_AMOUNT") && amount>=((Number)p.get("amount_threshold_minor")).longValue()){points+=add(catalog,"LARGE_AMOUNT",matched,reasons,"Amount exceeds the configured large-payment threshold");}
  double ratio=average>0?amount/average:0;
  double z=n>=5?(amount-average)/Math.max(deviation,average*0.25):0;
  if(enabled(catalog,"ANOMALY") && n>=5 && ratio>=3 && z>=3){points+=add(catalog,"ANOMALY",matched,reasons,String.format(Locale.ROOT,"Amount is %.1fx the account baseline; statistical deviation %.1f",ratio,z));}
  if(enabled(catalog,"NEW_DEVICE") && n>=5 && ((Number)history.get("known_device")).longValue()==0){points+=add(catalog,"NEW_DEVICE",matched,reasons,"Previously unseen device for this account");}
  if(enabled(catalog,"NEW_COUNTRY") && n>=5 && ((Number)history.get("known_country")).longValue()==0){points+=add(catalog,"NEW_COUNTRY",matched,reasons,"Previously unseen country for this account");}
  long recent=((Number)history.get("recent")).longValue()+1;
  if(enabled(catalog,"VELOCITY") && recent>=((Number)p.get("velocity_limit")).intValue()){points+=add(catalog,"VELOCITY",matched,reasons,recent+" transactions within five minutes");}
  if(enabled(catalog,"FAILED_ATTEMPTS") && ((Number)t.get("failed_attempts")).intValue()>=3){points+=add(catalog,"FAILED_ATTEMPTS",matched,reasons,"Three or more preceding failed attempts");}
  if(enabled(catalog,"BLOCKED_IP") && SignalValues.matchesIp((String)t.get("ip_address"),(String)catalog.get("BLOCKED_IP").get("match_values"))) points+=add(catalog,"BLOCKED_IP",matched,reasons,"Transaction IP matches the negative list");
  if(enabled(catalog,"BLOCKED_PHONE") && t.get("phone_number")!=null && Arrays.asList(((String)catalog.get("BLOCKED_PHONE").get("match_values")).split("\n")).contains(t.get("phone_number"))) points+=add(catalog,"BLOCKED_PHONE",matched,reasons,"Transaction phone matches the negative list");
  int basePoints=points;
  Map<String,Object> labResult=lab.evaluate(id);
  if("ACTIVE".equals(labResult.get("mode"))&&Boolean.TRUE.equals(labResult.get("matched"))){
   int contribution=RuleLab.number(labResult,"points");points+=contribution;matched.add("REPEATED_AMOUNT");
   reasons.add(labResult.get("matching_count")+" same-amount payments within "+labResult.get("window_minutes")+" minutes (+"+contribution+")");
  }
  if(n<5) reasons.add("Insufficient account history; behavioral checks need five prior events");
  if(Boolean.TRUE.equals(t.get("late_event"))) reasons.add("Late event: evaluated using earlier event-time history available at ingestion");
  if(reasons.isEmpty()) reasons.add("No configured risk signals triggered");
  int score=Math.min(100,points);
  String classification=score>=((Number)p.get("high_threshold")).intValue()?"HIGH_RISK":score>=((Number)p.get("review_threshold")).intValue()?"SUSPICIOUS":"NORMAL";
  String features=service.encode(Map.of("historyCount",n,"averageMinor",average,"amountRatio",ratio,"deviationScore",z,"fiveMinuteCount",recent,"method","rules + statistical anomaly detection","matchedRules",matched,"ruleSnapshot",rules,"ruleLab",labResult));
  db.update("UPDATE transactions SET status='SCORED',score=?,classification=?,explanation=?,features=?,policy_version=?,base_risk_points=? WHERE id=?",
    score,classification,service.encode(reasons),features,p.get("version"),basePoints,id);
  if("SHADOW".equals(labResult.get("mode"))&&Boolean.TRUE.equals(labResult.get("matched")))
   db.update("INSERT INTO rule_lab_observations(transaction_id,rule_version,matching_count,actual_score,proposed_score,configuration) VALUES(?,?,?,?,?,?) ON CONFLICT(transaction_id) DO NOTHING",
    id,labResult.get("version"),labResult.get("matching_count"),score,Math.min(100,basePoints+RuleLab.number(labResult,"points")),service.encode(labResult));
  if(!classification.equals("NORMAL")) db.update("INSERT INTO alerts(id,transaction_id) VALUES(?,?) ON CONFLICT(transaction_id) DO NOTHING",UUID.randomUUID(),id);
  if(score==100) holds.place(id,account,service);
  email.enqueue(id,classification,score);
  service.audit("risk-worker","TRANSACTION_SCORED",id,"score="+score+";policy="+p.get("version"));
  log.info("transaction_scored id={} score={} classification={}",id,score,classification);
 }
 private boolean enabled(Map<String,Map<String,Object>> rules,String code){return Boolean.TRUE.equals(rules.get(code).get("enabled"));}
 private int add(Map<String,Map<String,Object>> rules,String code,List<String> matched,List<String> reasons,String text){
  int points=((Number)rules.get(code).get("points")).intValue(); matched.add(code);reasons.add(text+" (+"+points+")");return points;
 }}


