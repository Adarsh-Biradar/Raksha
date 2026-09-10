package com.fraudshield;
import java.security.Principal;
import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

// Attaches the caller's organization id to the authenticated principal, so controllers can scope
// every query to the caller's own organization's data.
public class OrgUserDetails extends User {
 private final UUID orgId;
 public OrgUserDetails(String username,String password,Collection<? extends GrantedAuthority> authorities,UUID orgId){
  super(username,password,authorities);this.orgId=orgId;
 }
 public UUID orgId(){return orgId;}
 public static UUID of(Principal principal){
  if(principal instanceof Authentication a && a.getPrincipal() instanceof OrgUserDetails u) return u.orgId();
  throw new IllegalStateException("Authenticated principal has no organization");
 }
}
