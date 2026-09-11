package com.fraudshield;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuleLabTest {
 private static final Map<String,Object> POLICY=Map.of("review_threshold",30,"high_threshold",70);
 @Test void belowReviewThresholdIsNormal() {
  assertEquals("NORMAL",RuleLab.classify(29,POLICY));
 }
 @Test void atReviewThresholdIsSuspicious() {
  assertEquals("SUSPICIOUS",RuleLab.classify(30,POLICY));
 }
 @Test void atHighThresholdIsHighRisk() {
  assertEquals("HIGH_RISK",RuleLab.classify(70,POLICY));
 }
 @Test void aboveHighThresholdIsHighRisk() {
  assertEquals("HIGH_RISK",RuleLab.classify(100,POLICY));
 }
}
