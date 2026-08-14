package com.flatmaite.search;

import com.flatmaite.listing.Locality;
import com.flatmaite.listing.LocalityRepository;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deterministic name→locality resolution over names + aliases. Unresolvable names never become
 * hard filters — they degrade to free-text embedding signal.
 */
@Component
@RequiredArgsConstructor
public class LocalityResolver {

  private final LocalityRepository localities;
  private final Map<String, UUID> byToken = new LinkedHashMap<>();
  private final Map<UUID, String> nameById = new LinkedHashMap<>();

  @PostConstruct
  void load() {
    for (Locality l : localities.findAll()) {
      nameById.put(l.getId(), l.getName());
      byToken.put(l.getName().toLowerCase(Locale.ROOT), l.getId());
      for (String alias : l.getAliases()) {
        byToken.put(alias.toLowerCase(Locale.ROOT), l.getId());
      }
    }
  }

  public UUID resolve(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String needle = name.toLowerCase(Locale.ROOT).trim();
    UUID exact = byToken.get(needle);
    if (exact != null) {
      return exact;
    }
    for (Map.Entry<String, UUID> e : byToken.entrySet()) {
      if (needle.contains(e.getKey()) || e.getKey().contains(needle)) {
        return e.getValue();
      }
    }
    return null;
  }

  /** All localities whose name/alias appears in the text, in order of appearance. */
  public List<UUID> scan(String text) {
    String haystack = text.toLowerCase(Locale.ROOT);
    List<UUID> found = new ArrayList<>();
    for (Map.Entry<String, UUID> e : byToken.entrySet()) {
      if (haystack.contains(e.getKey()) && !found.contains(e.getValue())) {
        found.add(e.getValue());
      }
    }
    return found;
  }

  public String nameOf(UUID id) {
    return nameById.getOrDefault(id, "Mumbai");
  }
}
