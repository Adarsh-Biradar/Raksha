package com.fraudshield;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class AccountHolds {
 private final JdbcTemplate db;
 public AccountHolds(JdbcTemplate db){this.db=db;}
 public void lock(String account){db.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",account);}
 public List<Map<String,Object>> active(String account){return db.queryForList("SELECT alert_id,created_at FROM account_holds WHERE account_id=? AND released_at IS NULL ORDER BY created_at",account);}
 public boolean block(UUID id,String account,String actor,FraudService service,UUID orgId){
  var holds=active(account);if(holds.isEmpty())return false;
  db.update("UPDATE transactions SET status='BLOCKED',classification=NULL,score=NULL,explanation=? WHERE id=?",service.encode(List.of("Customer account is on hold after a 100/100 assessment. Resolve investigation "+holds.get(0).get("alert_id")+" before creating a new transaction. This blocked attempt will not be replayed.")),id);
  service.audit(actor,"TRANSACTION_BLOCKED",id,"account="+account+";case="+holds.get(0).get("alert_id"),orgId);return true;
 }
 public void place(UUID transaction,String account,FraudService service,UUID orgId){
  UUID alert=db.queryForObject("SELECT id FROM alerts WHERE transaction_id=?",UUID.class,transaction);
  if(db.update("INSERT INTO account_holds(alert_id,account_id) VALUES(?,?) ON CONFLICT DO NOTHING",alert,account)==1) service.audit("risk-worker","ACCOUNT_HELD",account,"score=100;case="+alert,orgId);
 }
 public void release(UUID alert,String account,String actor,FraudService service,UUID orgId){
  if(db.update("UPDATE account_holds SET released_at=now(),released_by=? WHERE alert_id=? AND released_at IS NULL",actor,alert)==1) service.audit(actor,"ACCOUNT_HOLD_RELEASED",account,"case="+alert+";remaining="+active(account).size(),orgId);
 }
}
