package com.fraudshield;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api/rules/catalog")
public class RuleController {
 final JdbcTemplate db; final FraudService service;
 public RuleController(JdbcTemplate db,FraudService service){this.db=db;this.service=service;}
 @GetMapping List<Map<String,Object>> list(){return db.queryForList("SELECT * FROM detection_rules ORDER BY code");}
 public record Update(@NotNull @Min(1) Integer version,@NotNull Boolean enabled,@NotNull @Min(1) @Max(100) Integer points,@NotNull @Size(max=10000) String matchValues){}
 @PutMapping("/{code}") @Transactional Map<String,Object> update(@PathVariable String code,@Valid @RequestBody Update input,Principal actor){
  UUID org=OrgUserDetails.of(actor);
  db.queryForMap("SELECT version FROM risk_policy WHERE org_id=? FOR UPDATE",org);
  String values=SignalValues.list(code,input.matchValues());
  int changed=db.update("UPDATE detection_rules SET enabled=?,points=?,match_values=?,version=version+1 WHERE code=? AND version=?",input.enabled(),input.points(),values,code,input.version());
  if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Rule changed or does not exist; reload rules and retry");
  db.update("UPDATE risk_policy SET version=version+1 WHERE org_id=?",org);
  service.audit(actor.getName(),"RULE_UPDATED",code,service.encode(Map.of("enabled",input.enabled(),"points",input.points(),"version",input.version()+1,"listEntries",values.isEmpty()?0:values.split("\n").length)),org);
  return db.queryForMap("SELECT * FROM detection_rules WHERE code=?",code);
 }
}
