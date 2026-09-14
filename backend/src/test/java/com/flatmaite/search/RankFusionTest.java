package com.flatmaite.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankFusionTest {

  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID c = UUID.randomUUID();
  private final UUID d = UUID.randomUUID();

  @Test
  void appearingInBothLists_beatsHighPlacementInOne() {
    // vector says A, B, C — lexical says C, A, D
    List<RankFusion.Fused> fused =
        RankFusion.fuse(List.of(List.of(a, b, c), List.of(c, a, d)));

    assertThat(fused).extracting(RankFusion.Fused::id).containsExactly(a, c, b, d);
    // k = 60: A = 1/61 + 1/62, C = 1/63 + 1/61, B = 1/62 (vector only), D = 1/63 (lexical only)
    assertThat(fused.get(0).rrf()).isCloseTo(1.0 / 61 + 1.0 / 62, within(1e-12));
    assertThat(fused.get(1).rrf()).isCloseTo(1.0 / 63 + 1.0 / 61, within(1e-12));
    assertThat(fused.get(2).rrf()).isCloseTo(1.0 / 62, within(1e-12));
    assertThat(fused.get(3).rrf()).isCloseTo(1.0 / 63, within(1e-12));
  }

  @Test
  void normalized_topIsOne_andMonotone() {
    List<RankFusion.Fused> fused =
        RankFusion.fuse(List.of(List.of(a, b, c), List.of(c, a, d)));

    assertThat(fused.get(0).normalized()).isEqualTo(1.0);
    for (int i = 1; i < fused.size(); i++) {
      assertThat(fused.get(i).normalized()).isLessThanOrEqualTo(fused.get(i - 1).normalized());
      assertThat(fused.get(i).normalized()).isGreaterThan(0.0);
    }
  }

  @Test
  void singleList_keepsOrder_andDecaysGently() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      ids.add(UUID.randomUUID());
    }

    List<RankFusion.Fused> fused = RankFusion.fuse(List.of(ids));

    assertThat(fused).extracting(RankFusion.Fused::id).containsExactlyElementsOf(ids);
    assertThat(fused.get(0).normalized()).isEqualTo(1.0);
    // rank 100 → (K + 1) / (K + 100) = 61 / 160
    assertThat(fused.get(99).normalized()).isCloseTo(61.0 / 160.0, within(1e-12));
  }

  @Test
  void emptyInput_yieldsEmptyOutput() {
    assertThat(RankFusion.fuse(List.<List<UUID>>of())).isEmpty();
    assertThat(RankFusion.fuse(List.of(List.<UUID>of(), List.<UUID>of()))).isEmpty();
  }

  @Test
  void ties_keepFirstAppearanceOrder() {
    // B is only in list 1 at rank 2; C is only in list 2 at rank 2 → equal RRF; B was seen first
    List<RankFusion.Fused> fused = RankFusion.fuse(List.of(List.of(a, b), List.of(a, c)));

    assertThat(fused).extracting(RankFusion.Fused::id).containsExactly(a, b, c);
    assertThat(fused.get(1).rrf()).isEqualTo(fused.get(2).rrf());
  }
}
