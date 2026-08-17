package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * The distinction that matters: a refinement leans on the previous search and must keep it, while a
 * self-contained request must throw it away. Getting this wrong strands users on an empty result set
 * built from constraints they never asked for.
 */
class NewQueryDetectorTest {

  private NewQueryDetector detector;

  @BeforeEach
  void setUp() {
    LocalityRepository repo = Mockito.mock(LocalityRepository.class);
    Mockito.when(repo.findAll())
        .thenReturn(
            List.of(
                locality("Powai", 19.1176, 72.9060),
                locality("Goregaon", 19.1663, 72.8526),
                locality("Andheri", 19.1197, 72.8468),
                locality("BKC", 19.0662, 72.8697)));
    LocalityResolver resolver = new LocalityResolver(repo);
    resolver.load();
    detector = new NewQueryDetector(resolver);
  }

  private static Locality locality(String name, double lat, double lng) {
    Locality l = Locality.builder().name(name).lat(lat).lng(lng).build();
    l.setId(UUID.nameUUIDFromBytes(name.getBytes()));
    return l;
  }

  @ParameterizedTest(name = "fresh search: \"{0}\"")
  @DisplayName("self-contained requests replace the session intent")
  @ValueSource(
      strings = {
        // the reported case: Hinglish, three anchors (room type + locality + budget)
        "Single sharing room chahiye powai me budget 40k hai",
        "private room in goregaon under 25000",
        "2bhk in andheri for 60k",
        "double sharing room near bkc 15k",
        "looking for a flatmate in powai with a 30k budget",
      })
  void detectsSelfContainedRequests(String query) {
    assertThat(detector.isSelfContained(query)).isTrue();
  }

  @ParameterizedTest(name = "refinement: \"{0}\"")
  @DisplayName("genuine refinements keep leaning on the prior intent")
  @ValueSource(
      strings = {
        "cheaper",
        "show me cheaper options",
        "only verified listings",
        "make it 30k",
        "same but in andheri",
        "closer to work",
        "no smokers please",
        "",
      })
  void treatsRefinementsAsContinuations(String query) {
    assertThat(detector.isSelfContained(query)).isFalse();
  }
}
