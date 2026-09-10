package com.fraudshield;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/organization")
public class OrganizationController {
 final JdbcTemplate db; final FraudService fraud;
 public OrganizationController(JdbcTemplate db,FraudService fraud){this.db=db;this.fraud=fraud;}
 @GetMapping Map<String,Object> get(Principal actor){return db.queryForMap("SELECT id,slug,name FROM organizations WHERE id=?",OrgUserDetails.of(actor));}
 public record Rename(@NotBlank @Size(max=100) String name){}
 @PutMapping @Transactional Map<String,Object> rename(@Valid @RequestBody Rename input,Principal actor){
  java.util.UUID org=OrgUserDetails.of(actor);
  db.update("UPDATE organizations SET name=? WHERE id=?",input.name(),org);
  fraud.audit(actor.getName(),"ORGANIZATION_RENAMED",org,input.name(),org);
  return get(actor);
 }
}
