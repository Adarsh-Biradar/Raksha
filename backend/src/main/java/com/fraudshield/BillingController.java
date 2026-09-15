package com.fraudshield;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/billing")
public class BillingController {
 private final JdbcTemplate db; private final FraudService fraud;
 public BillingController(JdbcTemplate db,FraudService fraud){this.db=db;this.fraud=fraud;}
 @GetMapping("/usage") Map<String,Object> usage(Principal actor){
  UUID org=OrgUserDetails.of(actor);
  Map<String,Object> o=db.queryForMap("SELECT plan,monthly_transaction_cap FROM organizations WHERE id=?",org);
  String period=YearMonth.now().toString();
  long used=db.queryForList("SELECT transactions_count FROM usage_counters WHERE org_id=? AND period=?",org,period)
   .stream().findFirst().map(r->((Number)r.get("transactions_count")).longValue()).orElse(0L);
  Object cap=o.get("monthly_transaction_cap");
  Map<String,Object> result=new LinkedHashMap<>();
  result.put("plan",o.get("plan"));result.put("period",period);result.put("used",used);result.put("cap",cap);
  result.put("remaining",cap==null?null:Math.max(0,((Number)cap).longValue()-used));
  return result;
 }
 public record PlanChange(@NotNull @Pattern(regexp="FREE|PRO|ENTERPRISE") String plan){}
 @PutMapping("/plan") @Transactional Map<String,Object> changePlan(@Valid @RequestBody PlanChange input,Principal actor){
  UUID org=OrgUserDetails.of(actor);
  Integer cap=switch(input.plan()){case "FREE"->500;case "PRO"->10000;default->null;};
  db.update("UPDATE organizations SET plan=?,monthly_transaction_cap=? WHERE id=?",input.plan(),cap,org);
  fraud.audit(actor.getName(),"BILLING_PLAN_CHANGED",org,input.plan(),org);
  return usage(actor);
 }
}
