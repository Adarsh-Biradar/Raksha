package com.fraudshield;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class SignalValuesTest {
 @Test void ipAcceptsValidIpv4() {
  assertEquals("203.0.113.5",SignalValues.ip("203.0.113.5"));
 }
 @Test void ipRejectsOutOfRangeOctet() {
  assertThrows(ResponseStatusException.class,()->SignalValues.ip("203.0.113.999"));
 }
 @Test void ipRejectsWrongPartCount() {
  assertThrows(ResponseStatusException.class,()->SignalValues.ip("203.0.113"));
 }
 @Test void ipNullOrBlankIsNull() {
  assertNull(SignalValues.ip(null));
  assertNull(SignalValues.ip("  "));
 }
 @Test void phoneNormalizesAndValidates() {
  assertEquals("+919999999999",SignalValues.phone("+91 (999) 999-9999"));
 }
 @Test void phoneRejectsMissingCountryCode() {
  assertThrows(ResponseStatusException.class,()->SignalValues.phone("9999999999"));
 }
 @Test void matchesIpExactAddress() {
  assertTrue(SignalValues.matchesIp("203.0.113.5","203.0.113.5"));
  assertFalse(SignalValues.matchesIp("203.0.113.6","203.0.113.5"));
 }
 @Test void matchesIpCidrRange() {
  assertTrue(SignalValues.matchesIp("203.0.113.200","203.0.113.0/24"));
  assertFalse(SignalValues.matchesIp("203.0.114.1","203.0.113.0/24"));
 }
 @Test void matchesIpNullAddressNeverMatches() {
  assertFalse(SignalValues.matchesIp(null,"203.0.113.0/24"));
 }
 @Test void listNormalizesAndDedupesBlockedPhones() {
  String result=SignalValues.list("BLOCKED_PHONE","+91 999-999-9999\n+919999999999");
  assertEquals("+919999999999",result);
 }
 @Test void listRejectsTooManyEntries() {
  StringBuilder many=new StringBuilder();
  for(int i=0;i<201;i++) many.append("+91").append(String.format("%09d",i)).append("\n");
  assertThrows(ResponseStatusException.class,()->SignalValues.list("BLOCKED_PHONE",many.toString()));
 }
 @Test void listRejectsUnsupportedRuleCode() {
  assertThrows(ResponseStatusException.class,()->SignalValues.list("LARGE_AMOUNT","1,2,3"));
 }
}
