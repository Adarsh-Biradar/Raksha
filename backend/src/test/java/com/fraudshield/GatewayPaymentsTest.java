package com.fraudshield;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Covers the payment-status state machine: captured/authorized/failed never regress once reached.
class GatewayPaymentsTest {
 @Test void createdAdvancesToAuthorized() {
  assertEquals("authorized",GatewayPayments.advance("created","authorized"));
 }
 @Test void authorizedAdvancesToCaptured() {
  assertEquals("captured",GatewayPayments.advance("authorized","captured"));
 }
 @Test void capturedNeverRegressesToAuthorized() {
  assertEquals("captured",GatewayPayments.advance("captured","authorized"));
 }
 @Test void capturedNeverRegressesToFailed() {
  assertEquals("captured",GatewayPayments.advance("captured","failed"));
 }
 @Test void authorizedNeverRegressesToCreated() {
  assertEquals("authorized",GatewayPayments.advance("authorized","created"));
 }
 @Test void failedIsStickyOverCreated() {
  assertEquals("failed",GatewayPayments.advance("failed","created"));
 }
 @Test void unknownTransitionDefaultsToCreated() {
  assertEquals("created",GatewayPayments.advance("created","created"));
 }
}
