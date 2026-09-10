package com.fraudshield;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
final class SignalValues {
 static String ip(String value) {
  if(value==null||value.isBlank()) return null;
  String s=value.trim(); String[] parts=s.split("\\.",-1);
  if(parts.length!=4) throw bad("Use a valid IPv4 address");
  for(String p:parts) if(!p.matches("0|[1-9][0-9]{0,2}")||Integer.parseInt(p)>255) throw bad("Use a valid IPv4 address");
  return s;
 }
 static String phone(String value) {
  if(value==null||value.isBlank())return null;
  String s=value.replaceAll("[ ()-]","");
  if(!s.matches("\\+[1-9][0-9]{6,14}"))throw bad("Phone must include + and country code, with 7–15 digits");
  return s;
 }
 static String list(String code,String value) {
  if(value==null||value.isBlank())return "";
  if(!Set.of("BLOCKED_IP","BLOCKED_PHONE").contains(code))throw bad("This rule does not accept a block list");
  Set<String> result=new LinkedHashSet<>();
  for(String item:value.split("[,\\r\\n]+")) {
   if(item.isBlank())continue;
   if(code.equals("BLOCKED_PHONE"))result.add(phone(item.trim()));
   else {
    String[] parts=item.trim().split("/",-1);
    if(parts.length>2)throw bad("Invalid IPv4 CIDR");
    String address=ip(parts[0]);
    if(address==null)throw bad("Missing IPv4 address");
    if(parts.length==2&&(!parts[1].matches("[0-9]{1,2}")||Integer.parseInt(parts[1])>32))throw bad("CIDR prefix must be 0–32");
    result.add(address+(parts.length==2?"/"+Integer.parseInt(parts[1]):""));
   }
  }
  if(result.size()>200)throw bad("Maximum 200 block-list entries per rule");
  return String.join("\n",result);
 }
 static boolean matchesIp(String address,String list) {
  if(address==null)return false;
  long candidate=number(address);
  for(String item:list.split("\n")) {
   if(item.isBlank())continue;
   String[] parts=item.split("/");
   int prefix=parts.length==2?Integer.parseInt(parts[1]):32;
   long mask=prefix==0?0:(0xffffffffL << (32-prefix)) & 0xffffffffL;
   if((candidate&mask)==(number(parts[0])&mask))return true;
  }
  return false;
 }
 static long number(String value){long n=0;for(String p:value.split("\\."))n=(n<<8)|Integer.parseInt(p);return n;}
 static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
