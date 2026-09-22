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

  /**
   * One ranked row. The last four components are this workstream's addition (spec §4.6): which
   * block of the page the row belongs to, and how far it is from the place the search is anchored
   * on. All four are null when nothing anchored the search — a query naming no locality gets a
   * citywide page with no distances to state, which is a different thing from an unplaceable name.
   *
   * <p>{@code commuteMinutes} and {@code minutesFromAnchor} are the same estimate but not the same
   * field: {@code commuteMinutes} is set only when the row carries a {@code commuteLabel} to show,
   * whereas {@code minutesFromAnchor} is filled whenever a distance was actually measured, so the
   * client can sort or group by it without inheriting the labelling rule.
   */
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
      String nearMissReason,
      RescueLadder.SearchTier tier,
      Double distanceKm,
      Integer minutesFromAnchor,
      String anchorName) {}

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

  /**
   * How the page as a whole reads. {@code anchorName} is null when nothing anchored the search, and
   * {@code headline} and {@code terminus} are null with it: a query naming no locality is citywide
   * with no fallback framing at all (spec §4.8), which is deliberately not the same case as a name
   * we could not place. {@code headline} is also null on a page that is simply healthy — three or
   * more exact matches need no explaining.
   */
  public record ResultSummary(
      String anchorName,
      int exactCount,
      int nearbyCount,
      int overBudgetCount,
      String headline,
      String terminus) {}

  /**
   * The city this search was confined to, stated rather than left for the UI to infer (spec §4.11).
   * {@code source} is {@code UNSET} — with a null {@code city} and a {@code prompt} to render — for
   * anyone who has no profile locality, including every anonymous searcher. That is an explicit
   * state, never a Mumbai default in disguise, and the results below the prompt are real.
   */
  public record CitySearch(String city, CityScope.Source source, String prompt) {}

  public record AiSearchResponse(
      UUID sessionId,
      SearchIntent intent,
      String providerMode,
      List<AiResult> homes,
      List<AiResult> flatmates,
      List<Relaxer> relaxers,
      String note,
      ResultSummary resultSummary,
      List<Choice> choices,
      CitySearch citySearch) {}

  public record CompareRow(String label, List<String> values, Integer bestIndex) {}

  public record CompareResponse(List<AiResult> items, List<CompareRow> rows, String summary) {}
}
