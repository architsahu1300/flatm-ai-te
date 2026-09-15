package com.flatmaite.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.ai.AiProviderConfig;
import com.flatmaite.ai.IntentLlm;
import com.flatmaite.common.config.FlatmaiteProperties;
import com.flatmaite.search.IntentArbiter;
import com.flatmaite.search.IntentLocalities;
import com.flatmaite.search.LocalityResolver;
import com.flatmaite.search.RefinementHeuristics;
import com.flatmaite.search.SearchIntent;
import com.flatmaite.search.SearchIntent.LocationRef;
import com.flatmaite.search.SearchPipeline;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the golden set through the real wiring — provider, database gazetteer, arbiter — and writes
 * a report. Reports the offline thresholds but never enforces them: a live run informs, the build
 * gate is the keyword parser. Activate with the "eval" profile; refuses the mock provider unless
 * EVAL_ALLOW_MOCK=true (a smoke test of the runner itself). Run from {@code backend/} — the report
 * path {@code target/eval/…} is resolved against the working directory.
 */
@Component
@Profile("eval")
@Slf4j
public class EvalRunner implements ApplicationRunner {

  private final SearchPipeline pipeline;
  private final IntentArbiter arbiter;
  private final LocalityResolver resolver;
  private final IntentLlm intentLlm;
  private final ObjectMapper mapper;
  private final FlatmaiteProperties props;
  private final String provider;
  private final String openaiKey;
  private final String geminiKey;
  private final long paceMs;
  private final Set<String> tags;
  private final int limit;
  private final boolean allowMock;

  public EvalRunner(
      SearchPipeline pipeline,
      IntentArbiter arbiter,
      LocalityResolver resolver,
      IntentLlm intentLlm,
      ObjectMapper mapper,
      FlatmaiteProperties props,
      @Value("${spring.ai.model.chat:openai}") String provider,
      @Value("${spring.ai.openai.api-key}") String openaiKey,
      @Value("${spring.ai.google.genai.api-key:}") String geminiKey,
      @Value("${EVAL_PACE_MS:4500}") long paceMs,
      @Value("${EVAL_TAGS:}") String tags,
      @Value("${EVAL_LIMIT:0}") int limit,
      @Value("${EVAL_ALLOW_MOCK:false}") boolean allowMock) {
    this.pipeline = pipeline;
    this.arbiter = arbiter;
    this.resolver = resolver;
    this.intentLlm = intentLlm;
    this.mapper = mapper;
    this.props = props;
    this.provider = provider;
    this.openaiKey = openaiKey;
    this.geminiKey = geminiKey;
    this.paceMs = paceMs;
    this.tags = tags.isBlank() ? Set.of() : Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    this.limit = limit;
    this.allowMock = allowMock;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    boolean mock = AiProviderConfig.useMock(props, provider, openaiKey, geminiKey);
    if (mock && !allowMock) {
      throw new IllegalStateException(
          "The eval profile needs a real provider: set FM_AI_PROVIDER=google-genai GEMINI_API_KEY=… (or OPENAI_API_KEY), or EVAL_ALLOW_MOCK=true to smoke-test the runner");
    }
    intentLlm.healthCheck();
    GoldenSet set = GoldenSet.load().filter(tags, limit);
    log.info("Intent eval: provider={} model={} cases={} pace={}ms", intentLlm.providerName(), intentLlm.model(), set.cases().size(), paceMs);

    Function<LocationRef, String> nameOf = ref -> ref.localityId() != null ? resolver.nameOf(ref.localityId()) : ref.name();
    long[] calls = {0};
    IntentEvaluator.Extractor extractor =
        (query, prior) -> {
          SearchIntent p = prior == null ? null : IntentLocalities.resolve(prior, resolver);
          return arbiter.decide(
              query,
              p,
              (q, pp) -> {
                if (needsProvider(q, pp)) {
                  pace(calls[0]++ > 0);
                }
                return pipeline.extractIntent(q, pp, null, "eval");
              });
        };

    long started = System.currentTimeMillis();
    EvalReport report =
        IntentEvaluator.run(set, extractor, nameOf, r -> log.info("{} {} {}", r.passed() ? "PASS" : r.knownGap() ? "GAP " : "FAIL", r.golden().id(), r.firstMismatch()));

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("provider", intentLlm.providerName());
    meta.put("model", intentLlm.model());
    meta.put("mock", mock);
    meta.put("cases", set.cases().size());
    meta.put("providerCalls", calls[0]);
    meta.put("elapsedMs", System.currentTimeMillis() - started);
    meta.put("paceMs", paceMs);
    meta.put("promptOverheadTokens", intentLlm.promptOverheadTokens());
    meta.put("thresholdsEnforced", false);

    String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    Path out = Path.of("target", "eval", intentLlm.providerName() + "-" + intentLlm.model() + "-" + stamp + ".json");
    Files.createDirectories(out.getParent());
    Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report.toJson(mapper, meta)));

    System.out.println();
    System.out.println(report.renderTable());
    System.out.printf("%ncase pass rate %.3f over %d gated cases (%d known gaps)%n", report.casePassRate(), report.gated().size(), report.knownGaps().size());
    System.out.println("slot accuracy " + report.slotAccuracy());
    System.out.println("tag pass rate " + report.tagPassRate());
    System.out.println("verdict accuracy " + report.verdictAccuracy());
    System.out.println("threshold violations (informational): " + EvalThresholds.violations(report));
    System.out.println("report: " + out.toAbsolutePath());
  }

  /**
   * SearchPipeline answers a heuristic refinement ("cheaper", "closer to work", …) without calling the
   * provider, so those attempts are neither paced nor counted. Identical (query, prior) pairs would be
   * served from the pipeline's cache and still count here — the golden set has none.
   */
  static boolean needsProvider(String query, SearchIntent prior) {
    return prior == null || RefinementHeuristics.apply(prior, query) == null;
  }

  /** Sleeps {@code paceMs} before every provider call except the first (notFirst == false). */
  private void pace(boolean notFirst) {
    if (notFirst && paceMs > 0) {
      try {
        Thread.sleep(paceMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("eval interrupted", e);
      }
    }
  }
}
