package com.flatmaite.ai;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The only code that writes {@code vector(1536)} columns. Vectors travel as pgvector text literals
 * ("[0.1,0.2,...]") cast server-side.
 */
@Component
@RequiredArgsConstructor
public class VectorStoreWriter {

  private final JdbcTemplate jdbcTemplate;

  public void writeListingEmbedding(UUID listingId, float[] embedding, String textHash) {
    jdbcTemplate.update(
        "UPDATE listings SET embedding = ?::vector, embedding_text_hash = ? WHERE id = ?",
        toVectorLiteral(embedding),
        textHash,
        listingId);
  }

  public void writeFlatmateEmbedding(UUID flatmateProfileId, float[] embedding, String textHash) {
    jdbcTemplate.update(
        "UPDATE flatmate_profiles SET embedding = ?::vector, embedding_text_hash = ? WHERE id = ?",
        toVectorLiteral(embedding),
        textHash,
        flatmateProfileId);
  }

  public static String toVectorLiteral(float[] embedding) {
    StringBuilder sb = new StringBuilder(embedding.length * 10 + 2).append('[');
    for (int i = 0; i < embedding.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(embedding[i]);
    }
    return sb.append(']').toString();
  }
}
