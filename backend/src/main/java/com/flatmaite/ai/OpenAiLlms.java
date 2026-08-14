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

  private static final String INTENT_SYSTEM =
      """
      You convert flat/flatmate search queries for Mumbai into a structured SearchIntent JSON.
      Rules:
      - Only extract what the user actually said. Unknown fields stay null. Never invent budgets or places.
      - Amounts: "25k" = 25000 rupees/month, "1 lakh" = 100000.
      - searchTarget: PROPERTIES for rooms/flats, FLATMATES when they look for a person, BOTH when genuinely both.
      - locations: names as the user said them (e.g. "BKC", "Andheri"). Do not guess ids; leave localityId null.
      - commuteTo: set when they mention working somewhere or wanting to be near/within X minutes of a place.
      - lifestyle.smoking: NO_SMOKERS when they don't want smokers. lifestyle.quiet: true when they want a calm/quiet home or no party house.
      - roomType: PRIVATE for a room of their own, SHARED for shared/sharing, ENTIRE for whole flats (nBHK for themselves).
      - freeText: any residual nuance not captured by structured fields.
      """;

  private static final String REFINE_SYSTEM =
      """
      You update an existing SearchIntent JSON given a follow-up message from the user.
      Apply the modification and return the FULL updated intent (not a delta).
      "cheaper" reduces budgetMax ~10%%. "closer" tightens commuteTo.maxMinutes ~20%%.
      Keep every field the user did not change. Unknown fields stay null.
      Current intent JSON:
      %s
      """;

  @RequiredArgsConstructor
  @Slf4j
  public static class OpenAiIntentLlm implements IntentLlm {

    private final ChatClient chatClient;
    private final KeywordIntentParser fallback;
    private final ObjectMapper objectMapper;
    private final String modelName;

    @Override
    public SearchIntent extract(String query, SearchIntent prior) {
      BeanOutputConverter<SearchIntent> converter = new BeanOutputConverter<>(SearchIntent.class);
      String system;
      if (prior == null) {
        system = INTENT_SYSTEM;
      } else {
        String priorJson;
        try {
          priorJson = objectMapper.writeValueAsString(prior);
        } catch (Exception e) {
          priorJson = "{}";
        }
        system = REFINE_SYSTEM.formatted(priorJson);
      }
      try {
        SearchIntent extracted = call(system, query, converter, null);
        return finish(extracted, query, prior);
      } catch (Exception first) {
        log.warn("Intent extraction failed, attempting repair: {}", first.getMessage());
        try {
          SearchIntent extracted = call(system, query, converter, first.getMessage());
          return finish(extracted, query, prior);
        } catch (Exception second) {
          log.warn("Intent repair failed, using keyword fallback: {}", second.getMessage());
          SearchIntent parsed = fallback.parse(query);
          return prior == null ? parsed : new MockLlms.MockIntentLlm(fallback).extract(query, prior);
        }
      }
    }

    private SearchIntent call(
        String system, String query, BeanOutputConverter<SearchIntent> converter, String repairHint) {
      String user =
          repairHint == null
              ? query
              : query + "\n\n(Your previous output was invalid: " + repairHint + ". Return valid JSON only.)";
      String raw =
          chatClient
              .prompt()
              .system(system + "\n" + converter.getFormat())
              .user(user)
              .call()
              .content();
      SearchIntent intent = converter.convert(raw);
      if (intent == null) {
        throw new IllegalStateException("Converter returned null");
      }
      return intent;
    }

    private static SearchIntent finish(SearchIntent extracted, String query, SearchIntent prior) {
      return extracted.toBuilder()
          .originalQuery(prior != null && prior.originalQuery() != null ? prior.originalQuery() : query)
          .freeText(extracted.freeText() == null ? query : extracted.freeText())
          .build();
    }

    @Override
    public String providerName() {
      return "openai";
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
      return "openai";
    }

    @Override
    public String model() {
      return modelName;
    }
  }
}
