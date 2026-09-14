package com.flatmaite.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flatmaite.search.SearchIntent;

/** What the refine prompt returns: the merged intent plus the model's read of the follow-up. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefineResult(SearchIntent intent, String mode) {}
