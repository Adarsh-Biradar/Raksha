package com.fraudshield;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class RuleLab {
 final JdbcTemplate db;
 public RuleLab(JdbcTemplate db){this.db=db;}
 // Same predicate is used by historical preview and live evaluation.
 static final String COUNT_SQL="""
 SELECT count(*) FROM transactions h
 WHERE h.status<>'BLOCKED' AND h.account_id=t.account_id AND h.currency=t.currency AND h.amount_minor=t.amount_minor
 AND h.occurred_at>=t.occurred_at-(? * interval '1 minute')
 AND (h.occurred_at,h.received_at,h.id)<(t.occurred_at,t.received_at,t.id)
 AND h.received_at<=t.received_at
 """;
 public Map<String,Object> configuration(UUID orgId){return db.queryForMap("SELECT * FROM rule_lab WHERE org_id=?",orgId);}
 public long count(UUID id,int minutes){
  return db.queryForObject("SELECT 1+("+COUNT_SQL+") FROM transactions t WHERE t.id=?",Long.class,minutes,id);
 }
 static int number(Map<String,Object> row,String key){return ((Number)row.get(key)).intValue();}
 static String classify(int score,Map<String,Object> policy){
  return score>=number(policy,"high_threshold")?"HIGH_RISK":score>=number(policy,"review_threshold")?"SUSPICIOUS":"NORMAL";
 }
 public Map<String,Object> evaluate(UUID id,UUID orgId){
  Map<String,Object> rule=new HashMap<>(configuration(orgId));
  long count="OFF".equals(rule.get("mode"))?0:count(id,number(rule,"window_minutes"));
  rule.put("matching_count",count);rule.put("matched",!"OFF".equals(rule.get("mode"))&&count>=number(rule,"repeat_count"));
  return rule;
 }
}

