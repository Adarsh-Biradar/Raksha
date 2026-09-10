package com.fraudshield;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api")
public class ApiController {
 final JdbcTemplate db; final FraudService service;
 public ApiController(JdbcTemplate db,FraudService service){this.db=db;this.service=service;}
 @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token){return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
 @GetMapping("/auth/me") Map<String,Object> me(Authentication a){return Map.of("email",a.getName(),"role",a.getAuthorities().iterator().next().getAuthority().replace("ROLE_",""));}
 @PostMapping("/transactions") ResponseEntity<?> ingest(@Valid @RequestBody FraudService.Input input,@RequestHeader("Idempotency-Key") String key,Principal p){
  return ResponseEntity.accepted().body(service.ingest(input,key,p.getName()));
 }
 @GetMapping("/transactions") List<Map<String,Object>> transactions(@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="ALL") String classification,@RequestParam(defaultValue="0") int page){
  if(search.length()>100||page<0||page>10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid filter");
  return db.queryForList("""
   SELECT id,event_id,account_id,amount_minor,currency,merchant,country,device_id,occurred_at,received_at,status,score,classification,late_event
   FROM transactions WHERE (account_id ILIKE ? OR merchant ILIKE ? OR event_id ILIKE ?)
   AND (?='ALL' OR classification=? OR status=?) ORDER BY received_at DESC,id LIMIT 50 OFFSET ?
   ""","%"+search+"%","%"+search+"%","%"+search+"%",classification,classification,classification,page*50);
 }
 Map<String,Object> one(String sql,Object... args){
  return db.queryForList(sql,args).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found"));
 }
 @GetMapping("/transactions/{id}") Map<String,Object> transaction(@PathVariable UUID id){
  Map<String,Object> result=one("SELECT * FROM transactions WHERE id=?",id); result.remove("payload_hash");return result;
 }
 @GetMapping("/dashboard") Map<String,Object> dashboard(){
  Map<String,Object> result=new HashMap<>(db.queryForMap("""
   SELECT count(*) total,count(*) FILTER(WHERE status='PENDING') pending,
   count(*) FILTER(WHERE classification='HIGH_RISK') high_risk,
   count(*) FILTER(WHERE classification='NORMAL') normal,
   coalesce(sum(amount_minor),0) volume_minor FROM transactions
   """));
  result.put("open_alerts",db.queryForObject("SELECT count(*) FROM alerts WHERE status<>'RESOLVED'",Long.class));
  result.put("confirmed_fraud",db.queryForObject("SELECT count(*) FROM alerts WHERE outcome='CONFIRMED_FRAUD'",Long.class));
  result.put("dead_jobs",db.queryForObject("SELECT count(*) FROM scoring_jobs WHERE state='DEAD'",Long.class));
  result.put("trend",db.queryForList("SELECT occurred_at::date AS day,count(*) total,count(*) FILTER(WHERE classification<>'NORMAL') flagged FROM transactions WHERE occurred_at>=now()-interval '7 days' GROUP BY 1 ORDER BY 1"));
  return result;
 }
 @GetMapping("/alerts") List<Map<String,Object>> alerts(){return db.queryForList("""
  SELECT a.*,t.account_id,t.amount_minor,t.merchant,t.score,t.classification,t.explanation
  FROM alerts a JOIN transactions t ON t.id=a.transaction_id ORDER BY (a.status='RESOLVED'),t.score DESC,a.created_at DESC LIMIT 100
  """);}
 public record CaseUpdate(@NotNull @Min(0) Integer version,@NotBlank String action,@Size(max=2000) String reason,@Size(max=50) String outcome){}
 @PostMapping("/alerts/{id}/action") @Transactional Map<String,Object> action(@PathVariable UUID id,@Valid @RequestBody CaseUpdate input,Principal actor){
  Map<String,Object> alert=one("SELECT * FROM alerts WHERE id=? FOR UPDATE",id);
  if(!alert.get("version").equals(input.version())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Case changed; refresh and retry");
  if("RESOLVED".equals(alert.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Case is already resolved");
  if(input.action().equals("CLAIM")) {
   if(alert.get("assignee")!=null&&!actor.getName().equals(alert.get("assignee"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Case is assigned to another analyst");
   db.update("UPDATE alerts SET status='INVESTIGATING',assignee=?,version=version+1 WHERE id=?",actor.getName(),id);
  } else if(input.action().equals("RESOLVE")){
   if(!actor.getName().equals(alert.get("assignee"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Claim the case before resolving");
   if(input.reason()==null||input.reason().isBlank()||input.outcome()==null||!Set.of("CONFIRMED_FRAUD","FALSE_POSITIVE").contains(input.outcome()))
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"An outcome and resolution reason are required");
   db.update("UPDATE alerts SET status='RESOLVED',outcome=?,resolution=?,resolved_at=now(),version=version+1 WHERE id=?",input.outcome(),input.reason(),id);
  } else throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown case action");
  service.audit(actor.getName(),"CASE_"+input.action(),id,input.reason()==null?"":input.reason());
  return one("SELECT * FROM alerts WHERE id=?",id);
 }
 public record Note(@NotBlank @Size(max=2000) String note){}
 @GetMapping("/alerts/{id}/notes") List<Map<String,Object>> notes(@PathVariable UUID id){one("SELECT id FROM alerts WHERE id=?",id);return db.queryForList("SELECT * FROM case_notes WHERE alert_id=? ORDER BY created_at",id);}
 @PostMapping("/alerts/{id}/notes") @Transactional Map<String,Object> note(@PathVariable UUID id,@Valid @RequestBody Note note,Principal actor){
  one("SELECT id FROM alerts WHERE id=?",id);UUID noteId=UUID.randomUUID();
  db.update("INSERT INTO case_notes(id,alert_id,author,note) VALUES(?,?,?,?)",noteId,id,actor.getName(),note.note());
  service.audit(actor.getName(),"CASE_NOTE_ADDED",id,"note="+noteId);return Map.of("id",noteId);
 }
 public record Scenario(@NotBlank String scenario){}
 @PostMapping("/simulations") Map<String,Object> simulate(@Valid @RequestBody Scenario input,Principal actor){return service.simulate(input.scenario(),actor.getName());}
 @GetMapping("/rules") Map<String,Object> rules(){return one("SELECT * FROM risk_policy WHERE id=1");}
 public record Policy(@Min(1) int version,@Min(1) @Max(100000000000L) long amountThresholdMinor,@Min(2) @Max(100) int velocityLimit,@Min(1) @Max(98) int reviewThreshold,@Min(2) @Max(100) int highThreshold){}
 @PutMapping("/rules") @Transactional Map<String,Object> rules(@Valid @RequestBody Policy p,Principal actor){
  if(p.highThreshold()<=p.reviewThreshold())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"High threshold must exceed review threshold");
  int changed=db.update("UPDATE risk_policy SET version=version+1,amount_threshold_minor=?,velocity_limit=?,review_threshold=?,high_threshold=? WHERE id=1 AND version=?",
   p.amountThresholdMinor(),p.velocityLimit(),p.reviewThreshold(),p.highThreshold(),p.version());
  if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Rules changed; refresh and retry");
  service.audit(actor.getName(),"POLICY_UPDATED","1",service.encode(p));return rules();
 }
 @GetMapping("/audit-events") List<Map<String,Object>> audit(){return db.queryForList("SELECT * FROM audit_events ORDER BY id DESC LIMIT 100");}
 @GetMapping("/jobs") List<Map<String,Object>> jobs(){return db.queryForList("SELECT * FROM scoring_jobs WHERE state='DEAD' ORDER BY available_at LIMIT 100");}
 @PostMapping("/jobs/{id}/retry") @Transactional Map<String,Object> retry(@PathVariable UUID id,Principal actor){
  Map<String,Object> job=one("SELECT * FROM scoring_jobs WHERE id=? FOR UPDATE",id);
  if(!"DEAD".equals(job.get("state")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only dead jobs can be retried");
  db.update("UPDATE scoring_jobs SET state='PENDING',attempts=0,available_at=now(),last_error=NULL WHERE id=?",id);
  db.update("UPDATE transactions SET status='PENDING' WHERE id=?",job.get("transaction_id"));
  service.audit(actor.getName(),"JOB_RETRIED",id,"Manual retry");return Map.of("ok",true);
 }
}
