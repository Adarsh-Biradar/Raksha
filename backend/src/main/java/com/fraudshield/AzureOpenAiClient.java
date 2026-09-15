package com.fraudshield;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AzureOpenAiClient {
 private final String endpoint,key,deployment,apiVersion;
 private final ObjectMapper json;
 private static class Transport {static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();}
 public AzureOpenAiClient(ObjectMapper json,@Value("${AZURE_OPENAI_ENDPOINT:}") String endpoint,@Value("${AZURE_OPENAI_API_KEY:}") String key,
  @Value("${AZURE_OPENAI_DEPLOYMENT:gpt-5.4-mini}") String deployment,@Value("${AZURE_OPENAI_API_VERSION:2024-08-01-preview}") String apiVersion){
  this.json=json;this.endpoint=endpoint.replaceAll("/+$","");this.key=key;this.deployment=deployment;this.apiVersion=apiVersion;
 }
 public boolean configured(){return !endpoint.isBlank()&&!key.isBlank()&&!deployment.isBlank();}
 public record Reply(String content,int promptTokens,int completionTokens){}
 public Reply chat(String systemPrompt,String userPrompt){
  if(!configured())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_API_KEY and AZURE_OPENAI_DEPLOYMENT.");
  try{
   Map<String,Object> body=Map.of("messages",List.of(
    Map.of("role","system","content",systemPrompt),
    Map.of("role","user","content",userPrompt)),
    "temperature",0.2,"max_tokens",400);
   var request=HttpRequest.newBuilder(URI.create(endpoint+"/openai/deployments/"+deployment+"/chat/completions?api-version="+apiVersion))
    .timeout(Duration.ofSeconds(20)).header("api-key",key).header("Content-Type","application/json")
    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8)).build();
   var response=Transport.HTTP.send(request,HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("Azure OpenAI rejected request: "+response.statusCode());
   JsonNode root=json.readTree(response.body());
   String content=root.path("choices").path(0).path("message").path("content").asText("");
   int promptTokens=root.path("usage").path("prompt_tokens").asInt(0);
   int completionTokens=root.path("usage").path("completion_tokens").asInt(0);
   return new Reply(content,promptTokens,completionTokens);
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}
  catch(ResponseStatusException e){throw e;}
  catch(Exception e){throw unavailable();}
 }
 private ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Azure OpenAI request failed; will be retried.");}
}
