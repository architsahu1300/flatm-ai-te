package com.flatmaite.search;

import com.flatmaite.flatmate.FlatmateDtos;
import com.flatmaite.listing.ListingDtos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class SearchDtos {

  private SearchDtos() {}

  public record AiSearchRequest(@NotBlank @Size(max = 600) String query, UUID sessionId) {}

  public record ExplainRequest(UUID sessionId, List<UUID> candidateIds) {}

  public record CompareRequest(UUID sessionId, @Size(min = 2, max = 4) List<UUID> candidateIds) {}

  public record AiResult(
      String kind, // "home" | "flatmate"
      int matchScore,
      List<MatchScorer.Component> scoreBreakdown,
      List<String> matchReasons,
      List<String> concerns,
      Integer commuteMinutes,
      String commuteLabel,
      ListingDtos.CardResponse home,
      FlatmateDtos.CardResponse flatmate,
      boolean nearMiss,
      String nearMissReason) {}

  public record Relaxer(String label, String description, SearchIntent relaxedIntent, long extraResults) {}

  /** What a clicked choice does. Only one action exists today: raise the stated budget. */
  public enum ChoiceAction {
    RAISE_BUDGET
  }

  /**
   * A counted, one-click compromise offered alongside the results — never applied automatically.
   * {@code value} is the new {@code budgetMax} a {@code RAISE_BUDGET} choice would set; {@code
   * count} is how many listings that actually opens up. The user's own {@link SearchIntent} does
   * not change until this is posted to {@code /api/v1/ai/apply} (see {@link
   * AiSearchController#apply}) — viewing or scoring an auto-shown over-budget row is not consent.
   */
  public record Choice(String label, ChoiceAction action, int value, long count) {}

  public record AiSearchResponse(
      UUID sessionId,
      SearchIntent intent,
      String providerMode,
      List<AiResult> homes,
      List<AiResult> flatmates,
      List<Relaxer> relaxers,
      List<Choice> choices,
      String note) {}

  public record CompareRow(String label, List<String> values, Integer bestIndex) {}

  public record CompareResponse(List<AiResult> items, List<CompareRow> rows, String summary) {}
}
