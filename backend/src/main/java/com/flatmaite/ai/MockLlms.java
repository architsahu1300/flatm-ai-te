package com.flatmaite.ai;

import com.flatmaite.search.KeywordIntentParser;
import com.flatmaite.search.SearchIntent;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** Key-free implementations: keyword parsing for intent, breakdown-templating for explanations. */
public final class MockLlms {

  private MockLlms() {}

  @RequiredArgsConstructor
  public static class MockIntentLlm implements IntentLlm {

    private final KeywordIntentParser parser;

    @Override
    public SearchIntent extract(String query, SearchIntent prior) {
      SearchIntent parsed = parser.parse(query);
      if (prior == null) {
        return parsed;
      }
      // refinement: parsed non-null fields override the prior
      return SearchIntent.builder()
          .searchTarget(parsed.searchTarget() != null && parsed.locations() != null
                  ? parsed.searchTarget()
                  : firstNonNull(parsed.searchTarget(), prior.searchTarget()))
          .locations(firstNonNull(parsed.locations(), prior.locations()))
          .budgetMin(firstNonNull(parsed.budgetMin(), prior.budgetMin()))
          .budgetMax(firstNonNull(parsed.budgetMax(), prior.budgetMax()))
          .roomType(firstNonNull(parsed.roomType(), prior.roomType()))
          .listingTypes(firstNonNull(parsed.listingTypes(), prior.listingTypes()))
          .furnished(firstNonNull(parsed.furnished(), prior.furnished()))
          .bhk(firstNonNull(parsed.bhk(), prior.bhk()))
          .moveInDate(firstNonNull(parsed.moveInDate(), prior.moveInDate()))
          .leaseMonths(firstNonNull(parsed.leaseMonths(), prior.leaseMonths()))
          .maxDeposit(firstNonNull(parsed.maxDeposit(), prior.maxDeposit()))
          .genderPreference(firstNonNull(parsed.genderPreference(), prior.genderPreference()))
          .couplesOk(firstNonNull(parsed.couplesOk(), prior.couplesOk()))
          .amenities(firstNonNull(parsed.amenities(), prior.amenities()))
          .lifestyle(mergeLifestyle(prior.lifestyle(), parsed.lifestyle()))
          .commuteTo(firstNonNull(parsed.commuteTo(), prior.commuteTo()))
          .verifiedOnly(firstNonNull(parsed.verifiedOnly(), prior.verifiedOnly()))
          .freeText(query)
          .originalQuery(prior.originalQuery() != null ? prior.originalQuery() : query)
          .build();
    }

    private static SearchIntent.Lifestyle mergeLifestyle(
        SearchIntent.Lifestyle prior, SearchIntent.Lifestyle parsed) {
      if (prior == null) return parsed;
      if (parsed == null) return prior;
      return SearchIntent.Lifestyle.builder()
          .quiet(firstNonNull(parsed.quiet(), prior.quiet()))
          .smoking(firstNonNull(parsed.smoking(), prior.smoking()))
          .pets(firstNonNull(parsed.pets(), prior.pets()))
          .diet(firstNonNull(parsed.diet(), prior.diet()))
          .drinking(firstNonNull(parsed.drinking(), prior.drinking()))
          .sleepSchedule(firstNonNull(parsed.sleepSchedule(), prior.sleepSchedule()))
          .cleanliness(firstNonNull(parsed.cleanliness(), prior.cleanliness()))
          .wfh(firstNonNull(parsed.wfh(), prior.wfh()))
          .partiesOk(firstNonNull(parsed.partiesOk(), prior.partiesOk()))
          .build();
    }

    private static <T> T firstNonNull(T a, T b) {
      return a != null ? a : b;
    }

    @Override
    public String providerName() {
      return "mock";
    }

    @Override
    public String model() {
      return "keyword-parser";
    }
  }

  public static class MockExplainerLlm implements ExplainerLlm {

    @Override
    public List<Explanation> explainBatch(SearchIntent intent, List<CandidateFacts> candidates) {
      List<Explanation> out = new ArrayList<>(candidates.size());
      for (CandidateFacts c : candidates) {
        out.add(
            new Explanation(
                c.id(),
                c.positives().stream().limit(3).toList(),
                c.concerns().stream().limit(2).toList()));
      }
      return out;
    }

    @Override
    public String providerName() {
      return "mock";
    }

    @Override
    public String model() {
      return "breakdown-template";
    }
  }
}
