package com.flatmaite.ai;

import com.flatmaite.common.config.FlatmaiteProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One cheap call per boot against the configured AI provider. The search pipeline is designed to
 * degrade silently (keyword parser + template explanations) when the LLM is unreachable — great for
 * uptime, terrible for diagnosis: a retired model or dead key looks exactly like working AI unless
 * you inspect explanation text. This turns that into a loud, actionable line in the startup log.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiProviderHealthCheck {

  private final EmbeddingProvider embeddingProvider;
  private final IntentLlm intentLlm;
  private final FlatmaiteProperties props;

  @EventListener(ApplicationReadyEvent.class)
  public void probe() {
    if ("mock".equals(embeddingProvider.providerName())) {
      return; // nothing to reach
    }
    String provider = embeddingProvider.providerName();

    try {
      embeddingProvider.embed("ping");
      log.info("AI health: embeddings OK ({})", provider);
    } catch (Exception e) {
      logDegraded("embeddings", provider, e);
    }

    if (!props.getAi().isExplanationsEnabled()) {
      return;
    }
    try {
      // cheapest real round-trip through the same ChatClient the pipeline uses
      intentLlm.healthCheck();
      log.info("AI health: chat model OK ({} / {})", provider, intentLlm.model());
    } catch (Exception e) {
      logDegraded("chat model " + intentLlm.model(), provider, e);
    }
  }

  private void logDegraded(String what, String provider, Exception e) {
    log.error(
        """

        ***************************************************************
        AI DEGRADED: {} unreachable via provider '{}'
        Reason: {}
        Search still works, but intent extraction falls back to the
        keyword parser and match explanations fall back to templates.
        Fix the key/model (see backend/.env.example) and restart.
        ***************************************************************
        """,
        what,
        provider,
        e.getMessage());
  }
}
