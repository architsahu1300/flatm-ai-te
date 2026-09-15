package com.flatmaite.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.search.KeywordIntentParser;
import com.flatmaite.search.SearchIntent;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

/**
 * OpenAI implementations via Spring AI. Every call is wrapped: parse failure → one repair retry →
 * deterministic fallback (keyword parser / breakdown templates). The pipeline never fails because
 * the LLM did.
 */
public final class OpenAiLlms {

  private OpenAiLlms() {}

  static final String INTENT_RULES =
      """
      You convert flat/flatmate search queries for Mumbai into a structured SearchIntent JSON.
      Rules:
      - Only extract what the user actually said. Unknown fields stay null. Never invent budgets or places.
      - Amounts: "25k" = 25000 rupees/month, "1 lakh" = 100000. budgetMax for ceilings ("under 25k"),
        budgetMin for floors ("more than 30000", "at least 20k"), both for ranges ("between 20k and 30k").
      - searchTarget: PROPERTIES for rooms/flats, FLATMATES when they look for a person, BOTH when genuinely both.
      - locations: where they want to live. excludeLocations: places to avoid ("anywhere but Andheri", "not in Bandra").
        Do not guess ids; leave localityId null.
      - commuteTo: set when they mention working somewhere or wanting to be near/within X minutes of a place.
      - lifestyle.smoking: NO_SMOKERS when they don't want smokers. lifestyle.quiet: true when they want a calm/quiet home or no party house.
      - freeText: any residual nuance not captured by structured fields.
      - Also return "confidence": an object mapping each field you filled to how directly the user's words
        state it — 1.0 when the user states it outright, 0.75 when their words imply it, 0.5 when you inferred it
        from context or convention. Rate the user's words, not your certainty about your own JSON. Omit the
        object entirely if unsure.
      """
          + com.flatmaite.search.RentalVocabulary.GLOSSARY;

  static final String EXAMPLES =
      """
      Examples (query → JSON, abbreviated to the fields that matter):
      "single sharing room in powai under 25k" → {"searchTarget":"PROPERTIES","locations":[{"name":"Powai"}],"budgetMax":25000,"roomType":"PRIVATE"}
      "Single sharing room chahiye powai me budget 40k hai" → {"searchTarget":"PROPERTIES","locations":[{"name":"Powai"}],"budgetMax":40000,"roomType":"PRIVATE"}
      "anywhere but Andheri, budget 25k" → {"searchTarget":"PROPERTIES","excludeLocations":[{"name":"Andheri East"},{"name":"Andheri West"}],"budgetMax":25000}
      "room in Andheri, I work at BKC" → {"searchTarget":"PROPERTIES","locations":[{"name":"Andheri East"},{"name":"Andheri West"}],"commuteTo":{"place":"BKC","maxMinutes":30},"roomType":"PRIVATE"}
      "flat for twenty five thousand in Malad" → {"searchTarget":"PROPERTIES","locations":[{"name":"Malad"}],"budgetMax":25000}
      "2bhk in Goregaon, more than 30000" → {"searchTarget":"PROPERTIES","locations":[{"name":"Goregaon"}],"bhk":{"min":2,"max":2},"budgetMin":30000,"roomType":"ENTIRE"}
      "between 20k and 30k near Dadar" → {"searchTarget":"PROPERTIES","commuteTo":{"place":"Dadar","maxMinutes":30},"budgetMin":20000,"budgetMax":30000}
      "female flatmate, no pets, quiet" → {"searchTarget":"FLATMATES","genderPreference":"FEMALE_ONLY","lifestyle":{"pets":"NO_PETS","quiet":true}}
      "1bhk near Hiranandani Gardens" → {"searchTarget":"PROPERTIES","commuteTo":{"place":"Powai","maxMinutes":30},"bhk":{"min":1,"max":1},"roomType":"ENTIRE"}
      """;

  /** Rules + glossary + the controlled locality vocabulary + worked examples. Built once per bean. */
  static String intentSystem(List<String> vocabulary) {
    return INTENT_RULES
        + "\nKnown localities — use these canonical names in locations, excludeLocations and commuteTo; map"
        + " landmarks and aliases onto them; a place not in this list goes into locations exactly as the"
        + " user wrote it:\n"
        + String.join("\n", vocabulary)
        + "\n\n"
        + EXAMPLES;
  }

  static final String REFINE_SYSTEM =
      """
      You update an existing SearchIntent JSON given the user's follow-up message.
      Apply the follow-up to the current intent and return the FULL merged intent — never drop
      fields the user did not change. "cheaper" reduces budgetMax ~10%. "closer" tightens
      commuteTo.maxMinutes ~20%. Unknown fields stay null.
      Also report mode: NEW if the follow-up reads as a complete request on its own, REFINE if it
      adjusts the current search, UNSURE otherwise. The caller decides what to do with it — you
      always merge.
      """;

  /** The prior intent contains the user's own words, so it travels in the user role, never the system role. */
  static String refineUserMessage(String priorJson, String query) {
    return "Current intent:\n" + priorJson + "\n\nFollow-up:\n" + query;
  }

  @Slf4j
  public static class OpenAiIntentLlm implements IntentLlm {

    /** Format instructions never change with the gazetteer — computed once, not per rebuild. */
    private static final BeanOutputConverter<SearchIntent> INTENT_FORMAT_CONVERTER =
        new BeanOutputConverter<>(SearchIntent.class);

    private final ChatClient chatClient;
    private final KeywordIntentParser fallback;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final String providerName;
    private final com.flatmaite.search.LocalityResolver localityResolver;

    // memoised system prompt + its token overhead, tagged with the resolver version they were
    // built from; reload() bumps the version so a stale "Known localities" block gets rebuilt
    // instead of living for the bean's whole lifetime. A benign race that rebuilds twice is fine.
    private volatile long intentSystemVersion = -1;
    private volatile String intentSystemCached;
    private volatile int promptOverheadTokensCached;

    public OpenAiIntentLlm(
        ChatClient chatClient,
        KeywordIntentParser fallback,
        ObjectMapper objectMapper,
        String modelName,
        String providerName,
        com.flatmaite.search.LocalityResolver localityResolver) {
      this.chatClient = chatClient;
      this.fallback = fallback;
      this.objectMapper = objectMapper;
      this.modelName = modelName;
      this.providerName = providerName;
      this.localityResolver = localityResolver;
    }

    /** Rebuilds the system prompt (and its token estimate) only when the resolver has moved on. */
    private String intentSystemCurrent() {
      long resolverVersion = localityResolver.version();
      if (resolverVersion != intentSystemVersion) {
        String built = intentSystem(localityResolver.vocabulary());
        intentSystemCached = built;
        promptOverheadTokensCached =
            com.flatmaite.search.AiUsageService.estimateTokens(built)
                + com.flatmaite.search.AiUsageService.estimateTokens(INTENT_FORMAT_CONVERTER.getFormat());
        intentSystemVersion = resolverVersion;
      }
      return intentSystemCached;
    }

    /** Test-only accessor for the currently cached system prompt. */
    String currentIntentSystem() {
      return intentSystemCurrent();
    }

    @Override
    public int promptOverheadTokens() {
      intentSystemCurrent();
      return promptOverheadTokensCached;
    }

    @Override
    public void healthCheck() {
      chatClient.prompt().user("Reply with the single word OK").call().content();
    }

    @Override
    public SearchIntent extract(String query, SearchIntent prior) {
      return extractWithMode(query, prior).intent();
    }

    @Override
    public Extraction extractWithMode(String query, SearchIntent prior) {
      if (prior == null) {
        BeanOutputConverter<SearchIntent> converter = new BeanOutputConverter<>(SearchIntent.class);
        try {
          return new Extraction(finish(call(intentSystemCurrent(), query, converter, null), query, null), Mode.NONE);
        } catch (Exception first) {
          log.warn("Intent extraction failed, attempting repair: {}", first.getMessage());
          try {
            return new Extraction(finish(call(intentSystemCurrent(), query, converter, first.getMessage()), query, null), Mode.NONE);
          } catch (Exception second) {
            log.warn("Intent repair failed, using keyword fallback: {}", second.getMessage());
            return new Extraction(fallback.parse(query), Mode.NONE);
          }
        }
      }
      BeanOutputConverter<RefineResult> converter = new BeanOutputConverter<>(RefineResult.class);
      String priorJson;
      try {
        priorJson = objectMapper.writeValueAsString(prior);
      } catch (Exception e) {
        priorJson = "{}";
      }
      String user = refineUserMessage(priorJson, query);
      try {
        RefineResult result = call(REFINE_SYSTEM, user, converter, null);
        return new Extraction(finish(result.intent(), query, prior), Mode.parse(result.mode()));
      } catch (Exception first) {
        log.warn("Intent refinement failed, attempting repair: {}", first.getMessage());
        try {
          RefineResult result = call(REFINE_SYSTEM, user, converter, first.getMessage());
          return new Extraction(finish(result.intent(), query, prior), Mode.parse(result.mode()));
        } catch (Exception second) {
          log.warn("Intent refinement repair failed, using keyword merge: {}", second.getMessage());
          return new Extraction(new MockLlms.MockIntentLlm(fallback).extract(query, prior), Mode.NONE);
        }
      }
    }

    private <T> T call(String system, String user, BeanOutputConverter<T> converter, String repairHint) {
      String content =
          repairHint == null
              ? user
              : user + "\n\n(Your previous output was invalid: " + repairHint + ". Return valid JSON only.)";
      String raw =
          chatClient
              .prompt()
              .system(system + "\n" + converter.getFormat())
              .user(content)
              .call()
              .content();
      T parsed = converter.convert(raw);
      if (parsed == null) {
        throw new IllegalStateException("Converter returned null");
      }
      return parsed;
    }

    /**
     * freeText is the LLM's residual when it gave one; otherwise the whole query on a first turn,
     * or the prior residual plus the follow-up's words on a refinement — so the message always
     * reaches retrieval even when the model omits the field, and never replaces the residual.
     */
    static SearchIntent finish(SearchIntent extracted, String query, SearchIntent prior) {
      String freeText = extracted.freeText();
      if (freeText == null) {
        freeText = prior == null ? query : SearchIntent.joinFreeText(prior.freeText(), query);
      }
      return extracted.toBuilder()
          .originalQuery(prior != null && prior.originalQuery() != null ? prior.originalQuery() : query)
          .freeText(freeText)
          .build();
    }

    @Override
    public String providerName() {
      return providerName;
    }

    @Override
    public String model() {
      return modelName;
    }
  }

  @RequiredArgsConstructor
  @Slf4j
  public static class OpenAiExplainerLlm implements ExplainerLlm {

    private static final String SYSTEM =
        """
        You explain why rental/flatmate matches fit a user's search. For each candidate you get
        VERIFIED FACTS (positives and concerns computed from real data). Rewrite them into short,
        natural first-person-addressed sentences ("✓ ₹3,000 under your budget" style, without the icons).
        STRICT RULES:
        - Only use the provided facts. NEVER invent amenities, prices, distances or people.
        - Never claim a match is perfect or guaranteed.
        - matchReasons: at most 3. concerns: at most 2 (empty list if no concern facts given).
        - Keep each sentence under 90 characters.
        """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final String providerName;

    @Override
    public List<Explanation> explainBatch(SearchIntent intent, List<CandidateFacts> candidates) {
      try {
        String payload =
            objectMapper.writeValueAsString(
                new Object() {
                  public final String userQuery = intent.originalQuery();
                  public final List<CandidateFacts> items = candidates;
                });
        List<Explanation> out =
            chatClient
                .prompt()
                .system(SYSTEM)
                .user("Explain these candidates:\n" + payload)
                .call()
                .entity(new ParameterizedTypeReference<List<Explanation>>() {});
        if (out == null || out.size() != candidates.size()) {
          throw new IllegalStateException("Unexpected explanation cardinality");
        }
        // ground-check: ids must belong to the batch
        List<UUID> allowed = candidates.stream().map(CandidateFacts::id).toList();
        if (!out.stream().allMatch(e -> allowed.contains(e.id()))) {
          throw new IllegalStateException("Explanation referenced unknown candidate");
        }
        return out;
      } catch (Exception e) {
        log.warn("Explanation LLM failed, using breakdown templates: {}", e.getMessage());
        return new MockLlms.MockExplainerLlm().explainBatch(intent, candidates);
      }
    }

    @Override
    public String providerName() {
      return providerName;
    }

    @Override
    public String model() {
      return modelName;
    }
  }
}
