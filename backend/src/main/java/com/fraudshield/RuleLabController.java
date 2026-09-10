package com.fraudshield;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api/rules/lab")
public class RuleLabController {
 final JdbcTemplate db;final RuleLab lab;final FraudService fraud;
 public RuleLabController(JdbcTemplate db,RuleLab lab,FraudService fraud){this.db=db;this.lab=lab;this.fraud=fraud;}
 public record Candidate(@Min(2) @Max(20) int repeatCount,@Min(1) @Max(60) int windowMinutes,@Min(1) @Max(100) int points,@NotNull @Size(max=80) String accountId){}
 public record Settings(@Min(1) int version,@NotNull @Pattern(regexp="OFF|SHADOW|ACTIVE") String mode,@Min(2) @Max(20) int repeatCount,@Min(1) @Max(60) int windowMinutes,@Min(1) @Max(100) int points){}
 @GetMapping Map<String,Object> get(Principal actor){return lab.configuration(OrgUserDetails.of(actor));}
 @PutMapping @Transactional Map<String,Object> save(@Valid @RequestBody Settings input,Principal actor){
  UUID org=OrgUserDetails.of(actor);
  db.queryForMap("SELECT version FROM risk_policy WHERE org_id=? FOR UPDATE",org);
  int changed=db.update("UPDATE rule_lab SET version=version+1,mode=?,repeat_count=?,window_minutes=?,points=? WHERE org_id=? AND version=?",input.mode(),input.repeatCount(),input.windowMinutes(),input.points(),org,input.version());
  if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Rule Lab changed; reload before saving");
  db.update("UPDATE risk_policy SET version=version+1 WHERE org_id=?",org);
  fraud.audit(actor.getName(),"RULE_LAB_UPDATED","REPEATED_AMOUNT",fraud.encode(input),org);
  return get(actor);
 }
 @PostMapping("/preview") @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
 Map<String,Object> preview(@Valid @RequestBody Candidate candidate,Principal actor){
  UUID org=OrgUserDetails.of(actor);
  Map<String,Object> policy=db.queryForMap("SELECT * FROM risk_policy WHERE org_id=?",org);
  List<Map<String,Object>> rows=db.queryForList("""
   SELECT t.id,t.event_id,t.account_id,t.merchant,t.amount_minor,t.occurred_at,t.score,
   coalesce(t.base_risk_points,t.score) base_points,
   a.outcome,1+(
   """+RuleLab.COUNT_SQL+"""
   ) matching_count
   FROM (SELECT * FROM transactions WHERE status='SCORED' AND org_id=? AND received_at>=now()-interval '30 days'
    AND (?='' OR account_id=?) ORDER BY received_at DESC,id DESC LIMIT 501) t
   LEFT JOIN alerts a ON a.transaction_id=t.id ORDER BY t.received_at DESC,t.id DESC
   """,candidate.windowMinutes(),org,candidate.accountId().trim(),candidate.accountId().trim());
  boolean limited=rows.size()>500;if(limited)rows=new ArrayList<>(rows.subList(0,500));
  int matches=0,changed=0,additional=0,reduced=0,knownFalse=0,labeled=0;
  List<Map<String,Object>> samples=new ArrayList<>();
  for(Map<String,Object> row:rows){
   boolean match=((Number)row.get("matching_count")).longValue()>=candidate.repeatCount();
   int recorded=RuleLab.number(row,"score");
   int proposed=Math.min(100,RuleLab.number(row,"base_points")+(match?candidate.points():0));
   String before=RuleLab.classify(recorded,policy),after=RuleLab.classify(proposed,policy);
   boolean newReview=before.equals("NORMAL")&&!after.equals("NORMAL");
   if(match)matches++;if(recorded!=proposed)changed++;if(newReview)additional++;
   if(!before.equals("NORMAL")&&after.equals("NORMAL"))reduced++;
   if(row.get("outcome")!=null)labeled++;
   if(!after.equals("NORMAL")&&"FALSE_POSITIVE".equals(row.get("outcome")))knownFalse++;
   if((match||recorded!=proposed)&&samples.size()<50){
    Map<String,Object> sample=new HashMap<>(row);sample.put("matched",match);sample.put("proposed_score",proposed);
    sample.put("before_classification",before);sample.put("proposed_classification",after);sample.put("additional_review",newReview);
    samples.add(sample);
   }
  }
  Map<String,Object> result=new LinkedHashMap<>();
  result.put("evaluated",rows.size());result.put("limited",limited);result.put("matched",matches);result.put("changed",changed);
  result.put("additionalReviews",additional);result.put("fewerReviews",reduced);result.put("labeled",labeled);result.put("knownFalsePositivesFlagged",knownFalse);
  result.put("samples",samples);result.put("candidate",candidate);result.put("policy",policy);result.put("labVersion",lab.configuration(org).get("version"));
  result.put("method","Latest 500 scored events received in 30 days; all available earlier history contributes. Other rule contributions stay frozen. Both classifications use current policy thresholds. This is an impact estimate, not measured fraud accuracy.");
  return result;
 }
 @GetMapping("/observations") List<Map<String,Object>> observations(Principal actor){return db.queryForList("""
  SELECT o.*,t.account_id,t.merchant,t.amount_minor FROM rule_lab_observations o
  JOIN transactions t ON t.id=o.transaction_id WHERE t.org_id=? ORDER BY o.created_at DESC LIMIT 100
  """,OrgUserDetails.of(actor));}
}
