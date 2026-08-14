package com.flatmaite.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic key-free embeddings: sha256(normalized text) seeds a mulberry32-style PRNG that
 * fills 1536 floats, L2-normalized. Identical texts collide exactly; token overlap adds mild
 * correlation via shingle mixing so "quiet flat" queries loosely attract "quiet" listings.
 */
public class MockEmbeddingProvider implements EmbeddingProvider {

  @Override
  public float[] embed(String text) {
    String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    float[] vector = new float[DIMENSIONS];

    // Base signal from the whole text
    mixInto(vector, normalized, 1.0f);
    // Token-level signal so shared words produce measurable cosine similarity
    for (String token : normalized.split("\\W+")) {
      if (token.length() >= 3) {
        mixInto(vector, token, 0.35f);
      }
    }
    return l2Normalize(vector);
  }

  @Override
  public List<float[]> embedBatch(List<String> texts) {
    List<float[]> out = new ArrayList<>(texts.size());
    for (String t : texts) {
      out.add(embed(t));
    }
    return out;
  }

  @Override
  public String providerName() {
    return "mock";
  }

  private static void mixInto(float[] vector, String text, float weight) {
    int state = seedFrom(text);
    for (int i = 0; i < vector.length; i++) {
      state = mulberry32(state);
      // map to [-1, 1)
      vector[i] += weight * ((state >>> 8) / 8388608.0f - 1.0f);
    }
  }

  private static int seedFrom(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return ((digest[0] & 0xff) << 24)
          | ((digest[1] & 0xff) << 16)
          | ((digest[2] & 0xff) << 8)
          | (digest[3] & 0xff);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static int mulberry32(int state) {
    state += 0x6D2B79F5;
    int t = state;
    t = (t ^ (t >>> 15)) * (t | 1);
    t ^= t + (t ^ (t >>> 7)) * (t | 61);
    return t ^ (t >>> 14);
  }

  private static float[] l2Normalize(float[] v) {
    double sum = 0;
    for (float x : v) {
      sum += (double) x * x;
    }
    float norm = (float) Math.sqrt(sum);
    if (norm > 0) {
      for (int i = 0; i < v.length; i++) {
        v[i] /= norm;
      }
    }
    return v;
  }
}
