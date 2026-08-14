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
      FlatmateDtos.CardResponse flatmate) {}

  public record Relaxer(String label, String description, SearchIntent relaxedIntent, long extraResults) {}

  public record AiSearchResponse(
      UUID sessionId,
      SearchIntent intent,
      String providerMode,
      List<AiResult> homes,
      List<AiResult> flatmates,
      List<Relaxer> relaxers,
      String note) {}

  public record CompareRow(String label, List<String> values, Integer bestIndex) {}

  public record CompareResponse(List<AiResult> items, List<CompareRow> rows, String summary) {}
}
