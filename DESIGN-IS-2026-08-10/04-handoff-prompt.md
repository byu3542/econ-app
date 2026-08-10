```
/make-plan Redesign EconomicDashboard's main dashboard screen (Android, Kotlin, View/Fragment/XML — app/src/main/java/com/economic/dashboard/ui/fragments/DashboardFragment.kt + app/src/main/res/layout/fragment_dashboard.xml, card_kpi_badge.xml, card_fed_funds_hero.xml). Current design failed audit at 15/30 with critical gaps in principles #2 (useful), #4 (understandable), #6 (honest), #8 (thorough).

Verdict paragraph (quoted from 03-verdict.md):
> Total score 15/30, below the 20-point REFINE threshold — the dashboard's bones (jargon-heavy labels, a mismatched "View history" affordance, zero accessibility labeling, three missing interaction states) span enough principles that patching them individually would touch structure, copy, and accessibility wiring across every card, which is a redesign-scale change, not a refine-scale one.

Why redesign and not refine: total score (15/30) is below the REFINE threshold of 20, driven by failures across structure, copy, and accessibility that can't be isolated to one file or one fix.

Preserve from current design:
- Card-token system: dimens.xml spacing scale (8/12/16dp used, fuller 2-20dp scale defined) and colors.xml token set (~85 tokens) — reuse as the base visual language, don't reinvent from zero.
- Component reuse pattern: card_kpi_badge include reused across 6 metrics, MetricGroupHeader reused across 3 section groups (fragment_dashboard.xml:92,99,102,109,116,119,126,133,136) — this reuse discipline is good, carry it forward.
- Indicator catalog data model: DashboardIndicatorCatalog.kt (labels/values feeding the cards) — redesign the labels' wording, not the underlying data plumbing.

Discard (structural patterns causing the failures):
- "View history →" affordance with no matching behavior (card_fed_funds_hero.xml:73) wired to a popup instead of history (DashboardFragment.kt:249-253). Caused failure on principle #6 (honest) and #4 (understandable).
- Zero-accessibility-labeling pattern across all card includes — no contentDescription anywhere in fragment_dashboard.xml, card_kpi_badge.xml, card_fed_funds_hero.xml, and sparkline explicitly hidden via importantForAccessibility="no" (card_kpi_badge.xml:86). Caused failure on principle #2 (useful).
- Incomplete state coverage (loading/success/partial-error only — DashboardFragment.kt:106-130) with no empty, focus, or disabled state design. Caused failure on principle #8 (thorough).
- Unexplained finance jargon baked directly into indicator labels and status strings (DashboardIndicatorCatalog.kt:36,38; DashboardFragment.kt:531-533) with no plain-language layer. Caused failure on principle #4 (understandable).

Top 3-5 moves from the audit (verbatim):
1. #4 Understandable / #6 Honest — Fix "View history →" (card_fed_funds_hero.xml:73) to either open real history or be relabeled to match MetricPopupController.show (DashboardFragment.kt:249-253); replace jargon labels ("CORE PCE INDEX," "10Y-3M SPREAD," "Cut cycle ongoing") with plain-language equivalents (DashboardIndicatorCatalog.kt:36,38; DashboardFragment.kt:531-533).
2. #2 Useful — Add contentDescription/accessibility labeling to every KPI card and the hero card (currently 0 across card_kpi_badge.xml, card_fed_funds_hero.xml); un-hide or describe the sparkline instead of importantForAccessibility="no" (card_kpi_badge.xml:86).
3. #8 Thorough — Design and implement empty, focus, and disabled states for the dashboard cards.
4. #9 Environmentally friendly — Reduce or stagger the 14 concurrent network fetch calls fired on cold launch (EconomicViewModel.kt:311-469).
5. #1 Innovative / #10 As little as possible — Reconsider the hero-card + 6-KPI-grid pattern itself; it's a standard finance-dashboard layout with no distinguishing mechanic, don't just re-skin it.

Redesign principles in priority order:
1. #2 Useful — every card and its data (label, value, trend, sparkline) must be reachable and understandable via TalkBack, not just sight
2. #4 Understandable — a first-time general-public user (not a finance professional) must be able to name what every card and control does without a glossary
3. #6 Honest — every visible label's claim must map 1:1 to what tapping it actually does

Deliverables for the plan:
- New information architecture for the dashboard screen (not derived from current XML structure)
- New primary flow (low-fi, labeled), compared side-by-side to current fragment_dashboard.xml layout
- States checklist: empty, loading, error, success, focus, disabled — designed and specified for every card type
- Accessibility spec: contentDescription/semantics content for hero card, KPI cards, and sparkline, plus focus-order guarantee that holds even when applyCategoryOrder() reorders cards at runtime (DashboardFragment.kt:284-318)
- Migration path for users currently on the old dashboard layout
- Cutover criteria for when the old fragment_dashboard.xml layout is retired

Anti-patterns to guard against (specific to REDESIGN):
- Porting the old card_kpi_badge/card_fed_funds_hero structure under new styling without fixing the accessibility and label-behavior gaps
- Keeping both old and new dashboard layouts behind a flag indefinitely
- Redesigning to chase a visual trend rather than the useful/understandable/honest principles above
- Treating the Preserve list as optional — the token system and component-reuse discipline must carry forward
```
