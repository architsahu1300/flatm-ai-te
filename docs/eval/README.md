# Intent eval — adding cases

`backend/src/main/resources/eval/intent-golden.json` is the answer key for intent extraction.

- One case = one message and the `SearchIntent` it should produce. **A slot you leave out of `expect`
  is expected to be null/empty** — write down everything the message implies, and nothing it does not.
- Always state `searchTarget` (`PROPERTIES`, `FLATMATES`, `BOTH`).
- Places are canonical names from `SeedLocalities` (`Andheri East`, `BKC`, `Lower Parel`, …). An
  ambiguous alias such as "andheri" expects both `Andheri East` and `Andheri West`.
- `commuteTo` is `{"place": "BKC", "maxMinutes": 30}`; the default radius is 30 when the message gives none.
- Follow-ups: `"prior": {"case": "<earlier id>"}` — the prior is that case's *expected* intent — and
  `"expectVerdict": "NEW" | "REFINE" | "AMBIGUOUS"`. Use `"scoreIntent": false` to grade only the verdict.
- `"mustPass": true` for rows that mirror a unit test or a shipped fix — these fail the build individually.
- `"known-gap"` in `tags` marks a row the keyword parser is known to miss (the live model is still
  graded on it). Known-gap rows are excluded from the offline aggregates and can never be must-pass.
- Ids are unique, kebab-case, prefixed by an abbreviation of their main tag (`basics-`, `budget-`,
  `loc-`, `excl-`, `commute-`, `life-`, `hi-`, `ref-`, `arb-`). Keep priors above the rows that use them.

Run the gate alone: `cd backend && ./mvnw -q test -Dtest=IntentGoldenTest` — a failure prints the
whole table with the first mismatch per case.

## Before a live run

The eval resolves localities from the database, so refresh it first: from the repo root
`docker compose down -v && docker compose up -d`, then
`cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=seed`. An older local seed
silently distorts the locality slots. Keep the console log of the run — see "Masked provider
errors" below.

## Key artefacts to read the live report against

Two places where the answer key encodes the keyword parser's defaults rather than a neutral reading.
A model that disagrees here is not necessarily wrong — read these rows with that in mind.

- **Shape-inferred `roomType`.** Bare "room" → `PRIVATE`; bare "nBHK", "apartment", "studio", "1RK" →
  `ENTIRE`; bare **"flat" → null** (only "entire/whole/full flat" is explicit). So `budget-lakh-numeric`
  ("apartment") expects `ENTIRE` while `loc-bkc-full` ("flat") expects null. A model that treats flat and
  apartment as synonyms loses `roomType` on one of those families whichever way it answers.
- **Glossary vs parser on nBHK.** The LLM's glossary says "2BHK only sets bhk"; the parser (and therefore
  the key) says bare nBHK → `ENTIRE`. Expect the model to leave `roomType` null on shape-only nBHK rows.
- **Masked provider errors.** The provider adapter retries once and then falls back to the keyword
  parser without failing the case, and the pipeline does the same for hard failures — so a rate limit
  (429) mid-run scores the *keyword parser* under the model's name. Discard any case whose console log
  shows `Intent extraction failed, attempting repair`, `Intent repair failed, using keyword fallback`,
  `Intent refinement failed, attempting repair`, `Intent refinement repair failed, using keyword merge`
  or `Intent extraction hard-failed, degrading to keyword parse`, and compare `meta.providerCalls` with
  the number you expected. Surfacing this in the JSON needs an `Extraction.source` field — deferred to WS4.
- **Estimated tokens only.** `meta.promptOverheadTokens` is the estimate the app charges against its
  daily AI budget; the provider's real usage is not captured yet (the provider adapter does not read
  response metadata — deferred to WS4).

Reconciling these is a prompt/vocabulary decision for after the first live run, sized by its numbers.
