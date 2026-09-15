package com.fraudshield;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
 @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }
 @Bean UserDetailsService users(JdbcTemplate db) {
  return email -> db.query("SELECT * FROM app_users WHERE email=?", (rs,n) ->
   new OrgUserDetails(rs.getString("email"),rs.getString("password_hash"),
    List.of(new SimpleGrantedAuthority("ROLE_"+rs.getString("role"))),(UUID)rs.getObject("org_id")), email)
   .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
 }
 static final UUID DEFAULT_ORG_ID=UUID.fromString("00000000-0000-0000-0000-000000000001");
 @Bean ApplicationRunner seed(JdbcTemplate db, PasswordEncoder encoder, @Value("${app.demo-password}") String password) {
  return args -> {
   if(password.length()<10) throw new IllegalArgumentException("DEMO_PASSWORD must contain at least 10 characters");
   db.update("INSERT INTO organizations(id,slug,name) VALUES(?,?,?) ON CONFLICT(id) DO NOTHING",DEFAULT_ORG_ID,"default","Default Organization");
   for(String role : new String[]{"ADMIN","ANALYST","VIEWER"}) {
    db.update("INSERT INTO app_users(email,password_hash,role,org_id) VALUES(?,?,?,?) ON CONFLICT(email) DO NOTHING",
      role.toLowerCase()+"@fraudshield.demo", encoder.encode(password),role,DEFAULT_ORG_ID);
   }
  };
 }
 @Bean SecurityFilterChain security(HttpSecurity http, RequestLoggingFilter requestLogging, RequestLimit requestLimit) throws Exception {
  http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/payments/webhook")).authorizeHttpRequests(auth -> auth
   .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
   .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/payments/webhook").permitAll()
   .requestMatchers("/api/csrf","/api/auth/login","/actuator/health","/actuator/health/**","/actuator/prometheus").permitAll()
   .requestMatchers("/api/rules/**","/api/audit-events","/api/jobs/**","/api/notifications/**","/api/ai/evaluations/**").hasRole("ADMIN")
   .requestMatchers(org.springframework.http.HttpMethod.PUT,"/api/organization","/api/billing/plan").hasRole("ADMIN")
   .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/**").hasAnyRole("ADMIN","ANALYST")
   .anyRequest().authenticated())
   .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
    .successHandler((req,res,a)->{res.setContentType("application/json");res.getWriter().write("{\"ok\":true}");})
    .failureHandler((req,res,e)->{res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Invalid email or password\"}");}))
   .logout(logout -> logout.logoutUrl("/api/auth/logout").logoutSuccessHandler((req,res,a)->res.setStatus(204)))
   .exceptionHandling(errors -> errors
    .authenticationEntryPoint((req,res,e)->res.sendError(401))
    .accessDeniedHandler((req,res,e)->res.sendError(403)))
   // Before CsrfFilter (not just before UsernamePasswordAuthenticationFilter): CsrfFilter runs
   // earlier in Spring Security's fixed filter order and rejects a bad/missing token itself, so a
   // limiter placed after it never sees - and never throttles - login attempts that skip CSRF
   // entirely (the common case for a scripted brute-force attempt).
   .addFilterBefore(requestLimit, org.springframework.security.web.csrf.CsrfFilter.class)
   .addFilterBefore(requestLogging, RequestLimit.class);
  return http.build();
 }
 // Distributed rate limiting: counts live in Redis (same instance backing sessions) so the 12/min
 // login and 600/min general limits hold across replicas, not just per-instance. Falls back to
 // permissive (never blocks) if Redis is briefly unreachable, rather than 500ing every request.
 @org.springframework.stereotype.Component
 static class RequestLimit extends OncePerRequestFilter {
  private final StringRedisTemplate redis;
  RequestLimit(StringRedisTemplate redis){this.redis=redis;}
  protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
   long minute=Instant.now().getEpochSecond()/60;
   boolean login=req.getRequestURI().equals("/api/auth/login");
   String key="raksha:ratelimit:"+req.getRemoteAddr()+(login?":login":":api")+":"+minute;
   try{
    Long count=redis.opsForValue().increment(key);
    if(count!=null&&count==1) redis.expire(key,Duration.ofSeconds(90));
    if(count!=null&&count>(login?12:600)) {res.setStatus(429);res.setHeader("Retry-After","60");res.setContentType("application/json");res.getWriter().write("{\"message\":\"Too many requests; retry in one minute\"}");return;}
   }catch(Exception redisUnavailable){/* fail open: don't let a Redis outage take down the API */}
   chain.doFilter(req,res);
  }
 }
}

