package com.fraudshield;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/evaluations")
public class AiEvalController {
 private final AiEvaluationService evaluations; private final JdbcTemplate db;
 public AiEvalController(AiEvaluationService evaluations,JdbcTemplate db){this.evaluations=evaluations;this.db=db;}
 @PostMapping("/run") Map<String,Object> run(Principal actor){return evaluations.run(actor.getName(),OrgUserDetails.of(actor));}
 @GetMapping List<Map<String,Object>> list(Principal actor){return db.queryForList("SELECT id,run_at,total,passed,failed FROM ai_eval_runs WHERE org_id=? ORDER BY run_at DESC LIMIT 50",OrgUserDetails.of(actor));}
}
