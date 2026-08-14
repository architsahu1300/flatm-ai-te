package com.flatmaite.listing;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReferenceController {

  private final LocalityRepository localities;
  private final AmenityRepository amenities;

  @GetMapping("/localities")
  public ResponseEntity<Map<String, Object>> localities() {
    return ResponseEntity.ok(Map.of("data", localities.findAll(Sort.by("name"))));
  }

  @GetMapping("/amenities")
  public ResponseEntity<Map<String, Object>> amenities() {
    return ResponseEntity.ok(Map.of("data", amenities.findAll(Sort.by("label"))));
  }
}
