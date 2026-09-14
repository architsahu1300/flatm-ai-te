package com.flatmaite.listing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListingQueryServiceWhereTest {

  @Test
  void excludeLocalities_addsANullSafeNotInClause() {
    UUID malad = UUID.randomUUID();
    Map<String, Object> params = new LinkedHashMap<>();

    String where =
        ListingQueryService.buildWhere(
            ListingFilters.builder().excludeLocalityIds(List.of(malad)).build(), params);

    // LEFT JOIN properties: a listing without a property must survive the exclusion
    assertThat(where).contains("(p.locality_id IS NULL OR p.locality_id NOT IN (:excludeLocalityIds))");
    assertThat(params).containsEntry("excludeLocalityIds", List.of(malad));
  }

  @Test
  void noExclusions_noClause() {
    Map<String, Object> params = new LinkedHashMap<>();

    String where = ListingQueryService.buildWhere(ListingFilters.empty(), params);

    assertThat(where).doesNotContain("NOT IN");
    assertThat(params).doesNotContainKey("excludeLocalityIds");
  }
}
