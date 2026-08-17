package com.flatmaite.agreement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatmaite.agreement.AgreementDtos.Clause;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;

/**
 * AI clause suggestions. Suggestions are never auto-applied — the user accepts each one. Mock mode
 * serves the curated optional pool; OpenAI mode drafts context-specific clauses and falls back to
 * the pool on any failure. Output is always labeled "not legal advice".
 */
public interface ClauseAdvisor {

  List<Clause> suggest(String context, List<Clause> existing);

  String providerName();

  @Slf4j
  @RequiredArgsConstructor
  class Mock implements ClauseAdvisor {
    @Override
    public List<Clause> suggest(String context, List<Clause> existing) {
      List<String> have = existing.stream().map(Clause::id).toList();
      return StandardClauses.optionalPool().stream()
          .filter(c -> !have.contains(c.id()))
          .limit(4)
          .toList();
    }

    @Override
    public String providerName() {
      return "mock";
    }
  }

  @Slf4j
  @RequiredArgsConstructor
  class OpenAi implements ClauseAdvisor {

    private static final String SYSTEM =
        """
        You draft optional clauses for a Maharashtra (India) residential Leave & License agreement.
        Rules:
        - Suggest at most 4 clauses relevant to the user's context that are NOT already covered.
        - Plain, neutral legal English. No statutory citations you are unsure of. Never invent
          amounts, dates or names — use "as agreed between the parties" phrasing.
        - Each clause: a short id (kebab-case), a title (max 6 words), and a body of 1-3 sentences.
        - These are drafts for review, not legal advice.
        """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String providerName;

    @Override
    public List<Clause> suggest(String context, List<Clause> existing) {
      try {
        String existingTitles =
            existing.stream().map(Clause::title).reduce((a, b) -> a + "; " + b).orElse("none");
        List<Clause> out =
            chatClient
                .prompt()
                .system(SYSTEM)
                .user("Context from the user: " + (context == null || context.isBlank() ? "(none)" : context)
                    + "\nClauses already present: " + existingTitles)
                .call()
                .entity(new ParameterizedTypeReference<List<Clause>>() {});
        if (out == null || out.isEmpty()) {
          throw new IllegalStateException("empty suggestion list");
        }
        return out.stream()
            .limit(4)
            .map(c -> new Clause(
                c.id() == null ? UUID.randomUUID().toString().substring(0, 8) : c.id(),
                c.title(),
                c.body(),
                "ai"))
            .toList();
      } catch (Exception e) {
        log.warn("Clause LLM failed, serving curated pool: {}", e.getMessage());
        return new Mock().suggest(context, existing);
      }
    }

    @Override
    public String providerName() {
      return providerName;
    }
  }
}
