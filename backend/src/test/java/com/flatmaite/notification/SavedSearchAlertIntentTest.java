package com.flatmaite.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.common.domain.RoomType;
import com.flatmaite.search.ConfidenceGate;
import com.flatmaite.search.SearchIntent;
import com.flatmaite.search.SearchIntent.LocationRef;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The gating bargain is "a soft slot stops filtering and starts ranking". The alert query does not
 * rank, so it cannot hold up its end — a soft slot there would simply be gone.
 */
class SavedSearchAlertIntentTest {

  @Test
  void aSavedSearchIsAnEndorsement_soEverySlotFiltersAgain() {
    SearchIntent saved =
        SearchIntent.builder()
            .locations(List.of(new LocationRef("Powai", UUID.randomUUID())))
            .roomType(RoomType.ENTIRE)
            .confidence(Map.of("locations", 0.58, "roomType", 0.5))
            .build();

    SearchIntent forAlert = SavedSearchAlertRunner.alertIntent(saved);

    assertThat(ConfidenceGate.softSlots(forAlert)).isEmpty();
    assertThat(ConfidenceGate.isHard(forAlert, "locations")).isTrue();
    assertThat(ConfidenceGate.isHard(forAlert, "roomType")).isTrue();
  }

  @Test
  void theSavedValuesThemselvesAreUntouched() {
    SearchIntent saved =
        SearchIntent.builder()
            .roomType(RoomType.PRIVATE)
            .budgetMax(25000)
            .confidence(Map.of("roomType", 0.5))
            .build();

    SearchIntent forAlert = SavedSearchAlertRunner.alertIntent(saved);

    assertThat(forAlert.roomType()).isEqualTo(RoomType.PRIVATE);
    assertThat(forAlert.budgetMax()).isEqualTo(25000);
  }
}
