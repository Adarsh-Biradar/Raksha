package com.fraudshield;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.mail.internet.InternetAddress;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/notifications")
public class NotificationController {
 final JdbcTemplate db; final FraudService fraud; final EmailAlerts mail;
 public NotificationController(JdbcTemplate db,FraudService fraud,EmailAlerts mail){this.db=db;this.fraud=fraud;this.mail=mail;}
 @GetMapping("/settings") Map<String,Object> settings(){
  Map<String,Object> result=new HashMap<>(db.queryForMap("SELECT * FROM notification_settings WHERE id=1"));
  result.put("smtp",Map.of("host",mail.host,"port",mail.port,"sender",mail.from,"configured",mail.configured(),"security","STARTTLS required"));
  return result;
 }
 public record Settings(@NotNull @Min(1) Integer version,@NotNull Boolean enabled,
 @NotNull @Pattern(regexp="HIGH_RISK|SUSPICIOUS") String minimumClassification,@NotNull @Size(max=6000) String recipients,
 @Pattern(regexp="|\\+[1-9][0-9]{7,14}") String merchantPhone){}
 @PutMapping("/settings") @Transactional Map<String,Object> save(@Valid @RequestBody Settings input,Principal actor){
  SortedSet<String> recipients=new TreeSet<>();
  for(String value:input.recipients().split("[,\\r\\n]+")){
   String email=value.trim().toLowerCase(Locale.ROOT);if(email.isEmpty())continue;
   try{
    if(email.length()>254||!email.matches("[^\\s<>;,]+@[^\\s<>;,]+\\.[^\\s<>;,]+"))throw new IllegalArgumentException();
    InternetAddress address=new InternetAddress(email,true);address.validate();
    if(!email.equals(address.getAddress())||address.getPersonal()!=null)throw new IllegalArgumentException();
   }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter valid email addresses, one per line");}
   recipients.add(email);
  }
  if(recipients.size()>20)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Maximum 20 management recipients");
  if(input.enabled()&&(recipients.isEmpty()||!mail.configured()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Configure SMTP and at least one recipient before enabling alerts");
  String merchantPhone=input.merchantPhone()==null||input.merchantPhone().isBlank()?null:input.merchantPhone().trim();
  int changed=db.update("UPDATE notification_settings SET version=version+1,enabled=?,minimum_classification=?,recipients=?,merchant_phone=? WHERE id=1 AND version=?",input.enabled(),input.minimumClassification(),String.join("\n",recipients),merchantPhone,input.version());
  if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Notification settings changed; reload and retry");
  fraud.audit(actor.getName(),"EMAIL_SETTINGS_UPDATED","1",fraud.encode(Map.of("enabled",input.enabled(),"minimumClassification",input.minimumClassification(),"recipientCount",recipients.size())));
  return settings();
 }
 @GetMapping("/deliveries") List<Map<String,Object>> deliveries(){return db.queryForList("SELECT * FROM email_deliveries ORDER BY created_at DESC LIMIT 100");}
 @GetMapping("/sms-deliveries") List<Map<String,Object>> smsDeliveries(){return db.queryForList("SELECT * FROM sms_deliveries ORDER BY created_at DESC LIMIT 100");}
 @PostMapping("/test") @Transactional Map<String,Object> test(Principal actor){
  Map<String,Object> settings=db.queryForMap("SELECT * FROM notification_settings WHERE id=1 FOR UPDATE");
  List<String> recipients=EmailAlerts.recipients(settings.get("recipients"));
  if(!mail.configured()||recipients.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Save at least one recipient and configure SMTP first");
  if(db.queryForObject("SELECT count(*) FROM email_deliveries WHERE classification='TEST' AND created_at>now()-interval '1 minute'",Long.class)>0)
   throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Wait one minute before another test");
  List<UUID> ids=new ArrayList<>();
  for(String recipient:recipients){UUID id=UUID.randomUUID();ids.add(id);db.update("INSERT INTO email_deliveries(id,recipient,classification) VALUES(?,?,'TEST')",id,recipient);}
  fraud.audit(actor.getName(),"EMAIL_TEST_QUEUED","1","Recipients="+recipients.size());
  return Map.of("queued",ids.size(),"ids",ids);
 }
 @PostMapping("/deliveries/{id}/retry") @Transactional Map<String,Object> retry(@PathVariable UUID id,Principal actor){
  Map<String,Object> settings=db.queryForMap("SELECT * FROM notification_settings WHERE id=1 FOR SHARE");
  List<Map<String,Object>> rows=db.queryForList("SELECT * FROM email_deliveries WHERE id=? FOR UPDATE",id);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Delivery not found");
  Map<String,Object> row=rows.get(0);
  if(!"FAILED".equals(row.get("state")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only failed deliveries can be retried");
  if(!mail.configured()||!EmailAlerts.recipients(settings.get("recipients")).contains(row.get("recipient"))||
   (!"TEST".equals(row.get("classification"))&&!Boolean.TRUE.equals(settings.get("enabled"))))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enable notifications and keep this recipient configured before retrying");
  db.update("UPDATE email_deliveries SET state='PENDING',attempts=0,available_at=now(),last_error=NULL WHERE id=?",id);
  fraud.audit(actor.getName(),"EMAIL_RETRY_QUEUED",id,"Manual retry");
  return Map.of("queued",true);
 }
}
