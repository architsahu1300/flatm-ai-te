# Google Stitch — Design Brief for "Homley"

> **PASTE ORDER — follow exactly:**
> 1. **PART A** (master brief) — once, to set context.
> 2. **PART B** (color exploration) — its own generation. Pick a winning scheme before continuing.
> 3. **PART D** (components sheet) — its own generation. This locks the design system.
> 4. **PART C** screen briefs, 3–5 screens per generation — and paste the **REPEAT BLOCK**
>    (top of Part C) above the screens **every single time**. Stitch treats generations as
>    largely independent; without the repeat block, later batches drift off-theme and
>    silently drop the dark/mobile variants.

---

## PART A — MASTER BRIEF (paste this first, keep it in every session)

Design a responsive web app called **Homley** — an AI-first flatmate & rental marketplace for urban India (launch city: Mumbai). It is NOT a property portal with a chatbot bolted on: **the AI is the search interface**. A user types what they want in plain words ("quiet furnished room near BKC under ₹25k, no smokers"), the app shows an editable interpretation of their request as chips, then ranked matches with a transparent Match Score and honest explanations — including concerns, not just positives.

**App name rule (important):** "Homley" is a working name. Render it ONLY as a plain text wordmark in the UI font — never as a drawn logo, lettermark, monogram, or text baked into imagery. The name must be swappable later by changing one text string. Where an icon is unavoidable (favicon slot, app icon), use an abstract spark/asterisk glyph that carries no letterforms.

**Audience:** 21–35 year-old renters and flat-sharers in Indian metros — working professionals and students, mobile-first, price-aware, scam-wary, design-literate (they use Airbnb, Zomato, Cred, Notion).

**The product must FEEL:** modern, warm, premium, trustworthy, human, intelligent, memorable.

**It must NEVER feel:** too colorful, childish, overly corporate, generic SaaS, like a dating app, like a stereotypical AI product (no neon-on-black cyber gradients, no sparkle-overload, no purple-to-cyan clichés), or like a traditional real-estate portal (no red/yellow urgency tags, no dense classified grids, no broker-site clutter).

**Design language:**
- Generous whitespace; strong typographic hierarchy; ONE primary CTA per view.
- Rounded geometry: 16px card radius, 10px control radius, full-pill chips.
- Two shadow levels only: a whisper at rest, a soft pop on hover/overlays. 1px hairline borders instead of heavy dividers.
- Typeface: Inter (or a close grotesque) — weights 400/500/600/700, tabular numerals for every price, score and count.
- Chips are the signature element: the AI's interpretation renders as editable pill chips (icon + label + value + remove ✕).
- Match Score renders as a small ring/donut with the % centered — treat it as a *measurement instrument*, colored by value (high = success tone, mid = caution tone, low = neutral), never in the brand color.
- Explanations use ✓ lines (positive, success tone) and ⚠ lines (concern, warning tone). Concerns are never hidden — honesty is the brand.
- Photography: real-feeling interior photos, warm light, never sterile stock renders.
- Currency is ₹ (Indian Rupee, en-IN formatting: ₹25,000).
- Layouts: mobile 390×844 AND desktop 1440×900 for every screen. Mobile uses a 5-tab bottom bar whose CENTER tab is a raised circular AI-search button (spark glyph); desktop uses a sticky top nav with a compressed "What are you looking for? ⌘K" search pill.
- Accessibility: all text ≥ 4.5:1 contrast on its surface in BOTH themes; never communicate by color alone.

---

## PART B — COLOR EXPLORATION (ask for this as its own generation)

Propose **5 distinct color schemes**, each delivered in BOTH a **light theme and a dark theme** (10 palettes total). The goal is a subtle but memorable color identity — one accent family doing precise work against calm neutrals, not a rainbow.

For EACH of the 5 schemes provide:
1. **A name and a one-paragraph rationale** — what mood it creates and why it fits "warm, premium, trustworthy, human, intelligent" while avoiding the anti-goals above.
2. **Full token table for light AND dark:** page background, card surface, secondary surface, hairline border, primary text, muted text, **brand/accent**, brand-hover, brand-soft (tinted chip background), success + success-soft, warning + warning-soft, danger + danger-soft. Give exact hex values, AA-checked against their surfaces.
3. **Match-ring mapping:** which tones represent scores ≥80, 60–79, <60 (must not use the brand accent).
4. **Applied samples:** the landing hero and one AI-search results screen rendered in that scheme (light + dark) so the schemes can be compared on real UI, not swatches.

Constraints for all 5 schemes:
- Neutrals carry 90% of every screen; the accent appears only on the primary CTA, active nav state, focus rings, links, and the AI-interpretation moments.
- Semantic green/amber/red are reserved exclusively for match reasons, concerns and destructive actions — they never decorate.
- Dark theme must be a true considered theme (elevated surfaces, softened accent, reduced saturation) — not an inverted light theme.
- Aim for 5 genuinely different personalities, e.g.: (a) warm terracotta/clay on cream neutrals, (b) deep indigo-violet on zinc, (c) forest/moss green on warm gray, (d) burnt amber/ochre with charcoal, (e) muted teal/petrol with sand — but propose your own if better. At least two schemes must be warm-first, and none may read as "generic SaaS blue".

---

## PART D — COMPONENTS SHEET (paste as its own generation, BEFORE any Part C screens)

Using the chosen color scheme, produce a single components sheet in **both light and dark theme**:
buttons (primary / outline / ghost / danger, in default + hover + disabled), text input, textarea,
search box (idle + focused + busy), filter chip, **intent chip** (icon + label + value + remove ✕),
lifestyle tag pill, **match ring at 92% / 71% / 48%**, verification badge, status badge
(Draft/Active/Paused/Rented), listing card, flatmate card, tabs / segmented control, bottom sheet,
modal, toast, **safety banner**, skeleton loaders, and empty state. Include the type scale
(12/13/14/16/18/24/32/40/56) and the two shadow levels. This sheet is the reference every
subsequent screen must follow.

---

## PART C — SCREEN BRIEFS

### REPEAT BLOCK — paste these lines above the screen briefs in EVERY batch

> Product: **Homley** — AI-first flatmate & rental marketplace, Mumbai. The AI *is* the search interface.
> Wordmark: plain text in the UI font only — never a drawn logo, monogram, or text inside imagery.
> Color scheme: **[PASTE CHOSEN SCHEME NAME + its light/dark tokens here]**. Follow the components sheet already produced.
> Render EVERY screen below in **both light and dark theme**, at **mobile 390×844 AND desktop 1440×900**.
> Language: generous whitespace, 16px card radius / 10px controls / pill chips, 1px hairline borders,
> two shadow levels, Inter, tabular numerals on all prices, scores and counts, ONE primary CTA per view.
> Accent appears only on the primary CTA, active nav, focus rings, links and AI moments.
> Green/amber are reserved for ✓ match reasons and ⚠ concerns — concerns are never hidden.
> Currency is ₹ with en-IN formatting (₹25,000). Mobile: 5-tab bottom bar with a raised centre AI-search button.
> Must feel modern, warm, premium, trustworthy, human, intelligent, memorable — and never colorful,
> childish, corporate, generic SaaS, dating-app, stereotypical-AI, or real-estate-portal.

---

### Screens

### C1. Live today (Phase 1)

1. **Landing page** — Sticky blur header (wordmark left, Browse homes / Find flatmates center, Sign in + Get started right). Hero: two-line headline "Find your next home. / Find the right person to share it with.", muted subline, then the hero element: a large AI search box (spark icon, rotating example placeholder, circular submit arrow) with 3 tappable example-query chips beneath, then three CTAs (Find my match — primary; Browse homes; Find a flatmate). Below: 5-step "How it works" timeline; a two-card split (Looking for a place? / Have a room to fill?); a quiet trust strip (AI-ranked matches · Verified users · ₹0 brokerage · Rental agreements); a "Popular in Mumbai" locality chip band; minimal footer. No carousels, no stock-photo hero.

2. **Sign in / Sign up** — Centered single card on calm background. Sign-in has an Email | Phone OTP segmented toggle; OTP flow shows a 6-digit code input state. Sign-up: name, email, password, then optional "Continue with Google" divider. Friendly microcopy, zero corporate sternness.

3. **Onboarding wizard (4 steps)** — Thin progress segments top-left, "Skip for now" top-right, one question per screen: ① "What brings you here?" as 5 icon option cards (find a room / find a flatmate / find a place with someone / list my property / just browsing); ② Basics (date of birth, gender chips, occupation chips); ③ Lifestyle (chip groups: cleanliness, social energy, schedule, smoking, drinking, food, pets, WFH); ④ Rental preferences (budget min/max, preferred-area chips, move-in date, room type). Ends on the AI search screen.

4. **AI Search — idle** — The product's heart. Near-empty screen, centered: "What are you looking for?" headline, large search box auto-focused, 3 example chips. Calm confidence, no clutter.

5. **AI Search — understanding state** — Same screen the moment after submit: a subtle "✦ Understanding your request…" line and a row of shimmering skeleton chips. This 1-second moment is a brand signature — design it with restraint (no big spinners, no robot mascots).

6. **AI Search — results** — Top: compact search box. Below: "Got it — here's what I understood" and the **interpreted-intent chip row** (📍 Location: BKC ✕ · 💰 Budget: ≤₹25,000 ✕ · 🛏 Private room ✕ · 🤫 Quiet home ✕ …) — each chip removable. Optional Homes(n) | Flatmates(n) tabs. Then ranked **match cards**: photo left (mobile: top), title, locality line, big tabular price + deposit, commute chip ("🚇 ~22 min to BKC (estimate)"), match ring (e.g., 87%), then up to 3 ✓ reason lines and up to 2 ⚠ concern lines, footer row with "Why this match?" link, Compare checkbox, View link. Docked at the bottom: a **refinement bar** — quick-refine chips ("Show me cheaper", "Only verified listings", "Show flatmates instead") above a rounded input with a Refine button.

7. **AI Search — expanded "Why this match?"** — The card opened to reveal the score breakdown: one row per component (budget, location, lifestyle, semantic, verification, quality, freshness) with a mini progress bar colored by value and a plain-language fact ("₹12,000 is ₹13,000 under your ₹25,000 budget"). Footer disclaimer: "Estimated from listing data and profiles. Always verify in person."

8. **AI Search — empty state with relaxers** — "No close matches for this" card followed by one-tap widening buttons with real counts: "Raise budget to ₹30,000 · shows 2 options", "Search all of Mumbai · shows 12 more". Never a dead end.

9. **Compare sheet** — Bottom sheet (mobile) / modal (desktop) comparing 2–3 selected matches: a one-line AI summary in a brand-soft strip, then a compact table (Match %, Rent, Deposit, Room, Furnishing, Commute, Available, Top concern) with the best value per row highlighted in success-soft.

10. **Explore (traditional browse)** — Desktop: persistent 280px left filter sidebar (locality chips, budget min/max, room type, furnishing, BHK, move-in date, lifestyle checkboxes, amenity chips, Clear all) beside a 3-column card grid with sort dropdown. Mobile: header row with "Filters (n)" button opening a full-height bottom sheet with sticky "Show n homes" footer; 1-column cards; Load more button. Listing cards: photo with verification badges overlaid, title, locality, tabular price, meta chips, occupants line.

11. **Listing detail** — Gallery with thumbnail strip; type + verification badges; title; "About this place"; household section (occupants, vibe chips); facts grid; amenity chips; approximate-location map (a soft brand-tinted 350m circle on a muted map — explicitly NOT an exact pin) with the caption "Approximate area — exact address shared after you connect"; sticky right rail (mobile: bottom section) with price, deposit, lister avatar + name, primary Message button, and a warning-soft safety note: "Never transfer a deposit before verifying the property and owner in person."

12. **Find a Flatmate (discovery)** — Light filter row (area chips, budget input, "Has a flat / Looking too" toggle) above a card grid of people: avatar, name + age, ✓ ID-verified tick, occupation, headline, budget + area + move-in chips, up to 5 lifestyle tag pills, **compatibility ring**, and the signature **shared-traits strip** in brand-soft italic: "✦ You both prefer quiet homes · Both non-smokers".

13. **Flatmate profile detail** — Large avatar, name/age/occupation + verified badge, compatibility ring, headline, shared-traits strip, About paragraph, full lifestyle facts grid (social style, cleanliness, schedule, smoking, drinking, food, pets, WFH, parties, guests, cooking), primary "Message {name}" button.

14. **Messages** — List view with Inbox | Requests tabs: avatar rows with name, "re: {listing}" context chip, preview, timestamp, unread count badge. **Request state:** an incoming first message shows sender summary + Accept / Decline / Block. **Thread view:** header with avatar + listing context chip + Block action; a dismissible warning-soft safety banner pinned in new threads; message bubbles (own = brand-soft right, theirs = neutral left) with subtle timestamps and read marks; sent-request state shows "Request sent — you can chat once they accept"; rounded composer.

15. **Saved** — Homes | Searches tabs. Homes: saved cards with filled hearts. Searches: named rows ("Room near BKC under 25k") with intent chips, "last run · n results" metadata, Run / Rename / Delete actions.

16. **My listings + creation wizard** — Dashboard rows: thumbnail, status badge (Draft/Active/Paused/Rented), title, price, Edit/Publish/Pause/Mark-rented actions. Wizard (6 steps with step rail + "Saved ✓" autosave hint): type & BHK → locality (with the "we only show an approximate circle publicly" privacy note) → details (title, description, rent/deposit/maintenance, availability, furnishing, amenity chips) → photo upload grid with cover tag → household & preferences (who lives here, preferred flatmate, vibe, smoking/pets/kitchen) → review-and-publish summary.

17. **Profile & settings** — Account card (avatar, name, email, Edit lifestyle); "Your flatmate card" manager: headline, about, situation toggle (Looking / Have a flat), budget range, area chips, Live/Hidden status badge with "Go live / Hide from discovery" toggle.

### C2. Coming next (Phase 2) — same system, design now

18. **Rental agreement wizard** — Steps: Parties (landlord + tenants) → Property → Terms (rent, deposit, duration, notice period, lock-in, escalation) → Clauses (standard clause list with add/edit; an "AI suggests" panel offering clauses as accept/reject cards with a clearly-worded "not legal advice" disclaimer) → Review (document preview) → status timeline (Draft → Finalized → Signed) with a Download PDF button. Feels like a calm document product, not legalese.

19. **Agreements list + detail** — Cards with parties, property, rent, status chips, version history; detail shows the timeline, signature status per party, PDF download.

20. **Notifications center** — Panel/page of grouped rows (new match, message request, listing status, agreement update) with unread dots, quiet timestamps, mark-all-read.

21. **Plans & boost (payments)** — A pricing view that is deliberately calm: Free vs Premium Seeker comparison card pair; a "Boost your listing" sheet with three duration options (₹99/3d · ₹199/7d · ₹299/14d) and an honest ⓘ note "Boosting raises visibility — it never changes match scores." Sponsored listings in results carry a small neutral "Featured" tag.

22. **Admin dashboard** — Left sidebar (Overview, Users, Listings, Reports, Verifications, AI usage). Overview: stat tiles + funnel (Search → Match → Contact → Connection); Users/Listings: dense-but-calm tables with suspend/remove actions; Reports: review queue cards with reason chips and resolve actions; Verifications: approval queue with evidence preview; AI usage: per-day cost table with a spend sparkline. Utilitarian but on-brand.

23. **Report / safety dialogs** — Report listing/user modal with reason chips (scam, fake listing, harassment, spam) + details field; block confirmation; a scam-warning interstitial style.

24. **SEO city/locality pages** — "/rent/mumbai/andheri": light editorial header (locality name, blurb, average-rent stat chips), listing card grid, related-locality chips, FAQ accordion. Indexable, fast-looking, zero portal clutter.

---

## Suggested batching (6 generations for 24 screens)

| Batch | Screens |
|-------|---------|
| 1 | 4, 5, 6, 7 — **the AI search flow; do this first, it's the product** |
| 2 | 1, 2, 3 — landing, auth, onboarding |
| 3 | 8, 9, 10, 11 — empty state, compare, explore, listing detail |
| 4 | 12, 13, 14 — flatmate discovery, flatmate profile, messages |
| 5 | 15, 16, 17 — saved, my-listings + wizard, profile |
| 6 | 18–24 — Phase 2 (agreements, notifications, plans, admin, safety, SEO) |

Batch 1 first is deliberate: the AI-search screens define the visual identity, so get them right
before the rest of the system inherits from them.
