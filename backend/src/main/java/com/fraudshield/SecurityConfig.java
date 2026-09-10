package com.fraudshield;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
   User.withUsername(rs.getString("email")).password(rs.getString("password_hash")).roles(rs.getString("role")).build(), email)
   .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
 }
 @Bean ApplicationRunner seed(JdbcTemplate db, PasswordEncoder encoder, @Value("${app.demo-password}") String password) {
  return args -> {
   if(password.length()<10) throw new IllegalArgumentException("DEMO_PASSWORD must contain at least 10 characters");
   for(String role : new String[]{"ADMIN","ANALYST","VIEWER"}) {
    db.update("INSERT INTO app_users(email,password_hash,role) VALUES(?,?,?) ON CONFLICT(email) DO NOTHING",
      role.toLowerCase()+"@fraudshield.demo", encoder.encode(password),role);
   }
  };
 }
 @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
  http.authorizeHttpRequests(auth -> auth
   .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
   .requestMatchers("/api/csrf","/api/auth/login","/actuator/health").permitAll()
   .requestMatchers("/api/rules/**","/api/audit-events","/api/jobs/**","/api/notifications/**").hasRole("ADMIN")
   .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/**").hasAnyRole("ADMIN","ANALYST")
   .anyRequest().authenticated())
   .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
    .successHandler((req,res,a)->{res.setContentType("application/json");res.getWriter().write("{\"ok\":true}");})
    .failureHandler((req,res,e)->{res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Invalid email or password\"}");}))
   .logout(logout -> logout.logoutUrl("/api/auth/logout").logoutSuccessHandler((req,res,a)->res.setStatus(204)))
   .exceptionHandling(errors -> errors
    .authenticationEntryPoint((req,res,e)->res.sendError(401))
    .accessDeniedHandler((req,res,e)->res.sendError(403)))
   .addFilterBefore(new RequestLimit(), UsernamePasswordAuthenticationFilter.class);
  return http.build();
 }
 static class RequestLimit extends OncePerRequestFilter {
  record Window(long minute,int count) {}
  private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
  protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
   long minute=Instant.now().getEpochSecond()/60;
   boolean login=req.getRequestURI().equals("/api/auth/login");
   String key=req.getRemoteAddr()+(login?":login":":api");
   if(windows.size()>10000) windows.entrySet().removeIf(e->e.getValue().minute()<minute);
   Window current=windows.compute(key,(k,w)->w==null||w.minute()!=minute?new Window(minute,1):new Window(minute,w.count()+1));
   if(current.count()>(login?12:600)) {res.setStatus(429);res.setHeader("Retry-After","60");res.setContentType("application/json");res.getWriter().write("{\"message\":\"Too many requests; retry in one minute\"}");return;}
   chain.doFilter(req,res);
  }
 }
}
