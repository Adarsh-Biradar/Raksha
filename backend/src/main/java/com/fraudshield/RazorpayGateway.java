package com.fraudshield;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RazorpayGateway {
 private final String key,secret,webhookSecret;
 private final ObjectMapper json;
 private static class Transport {static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();}
 public RazorpayGateway(ObjectMapper json,@Value("${RAZORPAY_KEY_ID:}") String key,@Value("${RAZORPAY_KEY_SECRET:}") String secret,@Value("${RAZORPAY_WEBHOOK_SECRET:}") String webhookSecret){this.json=json;this.key=key;this.secret=secret;this.webhookSecret=webhookSecret;}
 public boolean configured(){return key.startsWith("rzp_test_")&&!secret.isBlank()&&!webhookSecret.isBlank();}
 public String key(){requireConfigured();return key;}
 public void requireConfigured(){if(!configured())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure Razorpay TEST keys and a webhook secret on the server. Live keys are not supported.");}
 public JsonNode call(String method,String path,Object body){
  requireConfigured();
  try{
   var builder=HttpRequest.newBuilder(URI.create("https://api.razorpay.com/v1/"+path)).timeout(Duration.ofSeconds(15))
    .header("Authorization","Basic "+Base64.getEncoder().encodeToString((key+":"+secret).getBytes(StandardCharsets.UTF_8))).header("Content-Type","application/json");
   if(method.equals("GET"))builder.GET();else builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
   var response=Transport.HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("Provider rejected request");
   return json.readTree(response.body());
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}catch(Exception e){throw unavailable();}
 }
 private ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Razorpay request could not be confirmed. Use Reconcile; do not create another transaction to retry a payment.");}
 public void verifyWebhook(byte[] body,String signature){requireConfigured();verify(webhookSecret,body,signature);}
 public void verifyCheckout(String order,String payment,String signature){requireConfigured();verify(secret,(order+"|"+payment).getBytes(StandardCharsets.UTF_8),signature);}
 public static void verify(String secret,byte[] data,String signature){
  try{
   if(signature==null||!signature.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException();
   var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
   if(!MessageDigest.isEqual(mac.doFinal(data),HexFormat.of().parseHex(signature)))throw new IllegalArgumentException();
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid payment signature");}
 }
}

