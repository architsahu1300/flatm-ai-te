package com.flatmaite.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The offline gate's bar. The live runner reports these, it never enforces them. */
public final class EvalThresholds {

  public static final double MIN_CASE_PASS_RATE = 0.85;
  public static final double MIN_SLOT_ACCURACY = 0.90;
  public static final List<String> GATED_SLOTS = List.of("locations", "budgetMax", "roomType");

  private EvalThresholds() {}

  public static List<String> violations(EvalReport report) {
    List<String> out = new ArrayList<>();
    List<String> mustPass = report.mustPassFailures();
    if (!mustPass.isEmpty()) {
      out.add("must-pass cases failed: " + String.join(", ", mustPass));
    }
    if (report.casePassRate() < MIN_CASE_PASS_RATE) {
      out.add(String.format("case pass rate %.3f < %.2f", report.casePassRate(), MIN_CASE_PASS_RATE));
    }
    Map<String, Double> acc = report.slotAccuracy();
    for (String slot : GATED_SLOTS) {
      Double a = acc.get(slot);
      if (a != null && a < MIN_SLOT_ACCURACY) {
        out.add(String.format("slot %s accuracy %.3f < %.2f", slot, a, MIN_SLOT_ACCURACY));
      }
    }
    return out;
  }
}
