package com.fraudshield;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Correlation ID + access log for every request; enables tracing a single call across log lines and metrics.
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
 private static final Logger log=LoggerFactory.getLogger("access");
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
  String incoming=req.getHeader("X-Request-Id");
  String requestId=incoming!=null&&incoming.matches("[A-Za-z0-9_-]{1,64}")?incoming:UUID.randomUUID().toString();
  MDC.put("requestId",requestId);
  res.setHeader("X-Request-Id",requestId);
  long start=System.nanoTime();
  try{
   chain.doFilter(req,res);
  }finally{
   long tookMs=(System.nanoTime()-start)/1_000_000;
   log.info("method={} path={} status={} tookMs={}",req.getMethod(),req.getRequestURI(),res.getStatus(),tookMs);
   MDC.remove("requestId");
  }
 }
}
