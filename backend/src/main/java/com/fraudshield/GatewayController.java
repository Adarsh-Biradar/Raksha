package com.fraudshield;
import java.util.*;
import java.security.Principal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/payments")
public class GatewayController {
 private final GatewayPayments payments;private final RazorpayGateway gateway;
 public GatewayController(GatewayPayments payments,RazorpayGateway gateway){this.payments=payments;this.gateway=gateway;}
 @GetMapping("/config") Map<String,Object> config(){return Map.of("configured",gateway.configured(),"mode","TEST");}
 @GetMapping List<Map<String,Object>> list(Principal actor){return payments.list(OrgUserDetails.of(actor));}
 @GetMapping("/{id}") Map<String,Object> status(@PathVariable UUID id,Principal actor){return payments.status(id,OrgUserDetails.of(actor));}
 @PostMapping("/{id}/checkout") Map<String,Object> checkout(@PathVariable UUID id,Principal actor){return payments.checkout(id,actor.getName(),OrgUserDetails.of(actor));}
 @PostMapping("/{id}/reconcile") Map<String,Object> reconcile(@PathVariable UUID id,Principal actor){return payments.reconcile(id,actor.getName(),OrgUserDetails.of(actor));}
 public record Confirmation(@NotBlank @Pattern(regexp="pay_[A-Za-z0-9]{1,100}") String paymentId,@NotBlank @Pattern(regexp="[a-fA-F0-9]{64}") String signature){}
 @PostMapping("/{id}/confirm") Map<String,Object> confirm(@PathVariable UUID id,@Valid @RequestBody Confirmation c,Principal actor){return payments.confirm(id,c.paymentId(),c.signature(),actor.getName(),OrgUserDetails.of(actor));}
 @PostMapping("/webhook") Map<String,Object> webhook(@RequestBody byte[] raw,@RequestHeader(value="X-Razorpay-Signature",required=false) String signature,@RequestHeader(value="x-razorpay-event-id",required=false) String eventId){return payments.webhook(raw,signature,eventId);}
}
