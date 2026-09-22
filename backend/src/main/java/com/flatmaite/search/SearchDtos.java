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
   * on.
   *
   * <p>{@code tier} is always set on a home row — the ladder always has a tier 1, and a budget band
   * exists whether or not a place was named — and always null on a flatmate row.
   *
   * <p>{@code distanceKm}, {@code minutesFromAnchor} and {@code anchorName} are set only when the
   * search was actually <b>anchored</b> on a place, and are null together otherwise. "Otherwise"
   * includes a place the reader only inferred: an inference ranks but does not anchor, so it puts
   * no locality in the WHERE clause and there is no ring the page was searched around — a row may
   * not then claim "3.4 km from Kandivali" about a citywide result.
   *
   * <p>{@code anchorName} is the requested locality <b>this row</b> is nearest to, which for an
   * ambiguous alias ("andheri" → Andheri East and Andheri West) can differ from
   * {@code resultSummary.anchorName}: the summary names the placement's first locality, while a row
   * is measured from whichever of them it is actually closest to. Per-row that is the more useful
   * of the two, and the distance beside it is always measured from the name beside it.
   *
   * <p>{@code commuteMinutes} and {@code minutesFromAnchor} are the same estimate but not the same
   * field: {@code commuteMinutes} is set only when the row carries a {@code commuteLabel} to show,
   * whereas {@code minutesFromAnchor} is filled whenever the search was anchored and a distance was
   * measured, so the client can sort or group by it without inheriting the labelling rule.
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
   * How the page as a whole reads. Three different pages, told apart here rather than by the client
   * cross-referencing the intent (spec §4.8):
   *
   * <ul>
   *   <li>A place was named and placed — {@code anchorName} is set, and {@code headline} explains a
   *       page that needs explaining (nothing here, or fewer than three exact matches) or is null
   *       on a healthy one.
   *   <li>A place was named and nothing could place it — {@code anchorName} is null,
   *       {@code unplacedNames} holds what the user typed, and {@code headline} says so.
   *   <li>No place was named at all — {@code anchorName} null, {@code unplacedNames} empty, and no
   *       headline, no terminus, no framing of any kind. Citywide, exactly as before this
   *       workstream.
   * </ul>
   *
   * <p>{@code terminus} appears only when the fallback ladder ran out with a budget in play, which
   * is the one case where "no more" is a claim we can stand behind.
   */
  public record ResultSummary(
      String anchorName,
      List<String> unplacedNames,
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
