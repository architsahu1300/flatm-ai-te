package com.flatmaite.listing;

import com.flatmaite.ai.EmbeddingProvider;
import com.flatmaite.ai.EmbeddingTextComposer;
import com.flatmaite.ai.VectorStoreWriter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Regenerates a listing's embedding when its composed text changes (hash-checked). */
@Component
@RequiredArgsConstructor
@Slf4j
public class ListingEmbeddingRefresher {

  private final ListingRepository listings;
  private final PropertyRepository properties;
  private final LocalityRepository localities;
  private final EmbeddingProvider embeddingProvider;
  private final VectorStoreWriter vectorWriter;

  @Async
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void refresh(UUID listingId) {
    try {
      Listing listing = listings.findById(listingId).orElse(null);
      if (listing == null) {
        return;
      }
      String localityName =
          properties
              .findById(listing.getPropertyId() == null ? new UUID(0, 0) : listing.getPropertyId())
              .flatMap(p -> localities.findById(p.getLocalityId()))
              .map(Locality::getName)
              .orElse("Mumbai");
      String text = EmbeddingTextComposer.composeListing(listing, localityName);
      String hash = EmbeddingTextComposer.sha256(text);
      if (hash.equals(listing.getEmbeddingTextHash())) {
        return;
      }
      float[] vector = embeddingProvider.embed(text);
      vectorWriter.writeListingEmbedding(listingId, vector, hash);
    } catch (Exception e) {
      log.warn("Embedding refresh failed for listing {}", listingId, e);
    }
  }
}
