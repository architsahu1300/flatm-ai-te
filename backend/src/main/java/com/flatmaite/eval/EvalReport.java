package com.flatmaite.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flatmaite.eval.IntentComparator.SlotResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Aggregates over a run. Known-gap cases are reported but never counted in the gated figures. */
public final class EvalReport {

  private final List<CaseResult> results;

  public EvalReport(List<CaseResult> results) {
    this.results = List.copyOf(results);
  }

  public List<CaseResult> results() {
    return results;
  }

  public List<CaseResult> gated() {
    return results.stream().filter(r -> !r.knownGap()).toList();
  }

  public List<CaseResult> knownGaps() {
    return results.stream().filter(CaseResult::knownGap).toList();
  }

  public double casePassRate() {
    List<CaseResult> g = gated();
    return g.isEmpty() ? 1.0 : (double) g.stream().filter(CaseResult::passed).count() / g.size();
  }

  /** Per slot, over gated cases where the slot is present on either side (both "null"/"" = absent). */
  public Map<String, Double> slotAccuracy() {
    Map<String, Double> out = new LinkedHashMap<>();
    for (String slot : IntentComparator.SLOTS) {
      int present = 0;
      int matched = 0;
      for (CaseResult r : gated()) {
        for (SlotResult s : r.slots()) {
          if (!s.slot().equals(slot) || (absent(s.expected()) && absent(s.actual()))) {
            continue;
          }
          present++;
          if (s.match()) {
            matched++;
          }
        }
      }
      if (present > 0) {
        out.put(slot, (double) matched / present);
      }
    }
    return out;
  }

  private static boolean absent(String rendered) {
    return rendered == null || rendered.isEmpty() || rendered.equals("null");
  }

  public Map<String, Double> tagPassRate() {
    Map<String, int[]> counts = new TreeMap<>();
    for (CaseResult r : gated()) {
      for (String tag : r.golden().tags()) {
        int[] c = counts.computeIfAbsent(tag, t -> new int[2]);
        c[0]++;
        if (r.passed()) {
          c[1]++;
        }
      }
    }
    Map<String, Double> out = new LinkedHashMap<>();
    counts.forEach((tag, c) -> out.put(tag, (double) c[1] / c[0]));
    return out;
  }

  /** Over gated cases with an expected verdict; null when there are none. */
  public Double verdictAccuracy() {
    List<CaseResult> withVerdict = gated().stream().filter(r -> r.golden().expectVerdict() != null).toList();
    if (withVerdict.isEmpty()) {
      return null;
    }
    return (double) withVerdict.stream().filter(CaseResult::verdictMatches).count() / withVerdict.size();
  }

  public List<CaseResult> failures() {
    return gated().stream().filter(r -> !r.passed()).toList();
  }

  public List<String> mustPassFailures() {
    return failures().stream().filter(r -> r.golden().mustPass()).map(r -> r.golden().id()).toList();
  }

  /** Header + one line per case: STATUS  id  [tags]  first mismatch. */
  public String renderTable() {
    List<String> lines = new ArrayList<>();
    lines.add(String.format("%-4s  %-40s  %-28s  %s", "", "case", "tags", "first mismatch"));
    for (CaseResult r : results) {
      String status = r.knownGap() ? "GAP" : r.passed() ? "PASS" : "FAIL";
      lines.add(String.format("%-4s  %-40s  %-28s  %s", status, r.golden().id(), String.join(",", r.golden().tags()), r.firstMismatch()));
    }
    return String.join("\n", lines);
  }

  public ObjectNode toJson(ObjectMapper mapper, Map<String, Object> meta) {
    ObjectNode root = mapper.createObjectNode();
    root.set("meta", mapper.valueToTree(meta));
    root.put("cases_total", results.size());
    root.put("cases_gated", gated().size());
    root.put("casePassRate", casePassRate());
    root.set("slotAccuracy", mapper.valueToTree(slotAccuracy()));
    root.set("tagPassRate", mapper.valueToTree(tagPassRate()));
    Double v = verdictAccuracy();
    if (v == null) {
      root.putNull("verdictAccuracy");
    } else {
      root.put("verdictAccuracy", v);
    }
    root.set("mustPassFailures", mapper.valueToTree(mustPassFailures()));
    root.set("thresholdViolations", mapper.valueToTree(EvalThresholds.violations(this)));
    ArrayNode cases = root.putArray("cases");
    for (CaseResult r : results) {
      ObjectNode c = cases.addObject();
      c.put("id", r.golden().id());
      c.set("tags", mapper.valueToTree(r.golden().tags()));
      c.put("query", r.golden().query());
      c.put("passed", r.passed());
      c.put("knownGap", r.knownGap());
      c.put("verdict", r.verdict() == null ? null : r.verdict().name());
      c.put("firstMismatch", r.firstMismatch());
      c.put("millis", r.millis());
      ArrayNode slots = c.putArray("mismatches");
      for (SlotResult s : r.slots()) {
        if (!s.match()) {
          slots.addObject().put("slot", s.slot()).put("expected", s.expected()).put("actual", s.actual());
        }
      }
    }
    return root;
  }
}
