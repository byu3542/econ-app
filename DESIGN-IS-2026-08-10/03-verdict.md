# Verdict: REDESIGN

Total score 15/30, below the 20-point REFINE threshold — the dashboard's bones (jargon-heavy labels, a mismatched "View history" affordance, zero accessibility labeling, three missing interaction states) span enough principles that patching them individually would touch structure, copy, and accessibility wiring across every card, which is a redesign-scale change, not a refine-scale one.

Top moves:
1. **#4 Understandable / #6 Honest** — Fix "View history →" (card_fed_funds_hero.xml:73) to either open real history or be relabeled to match `MetricPopupController.show` (DashboardFragment.kt:249-253); replace jargon labels ("CORE PCE INDEX," "10Y-3M SPREAD," "Cut cycle ongoing") with plain-language equivalents (DashboardIndicatorCatalog.kt:36,38; DashboardFragment.kt:531-533).
2. **#2 Useful** — Add contentDescription/accessibility labeling to every KPI card and the hero card (card_kpi_badge.xml, card_fed_funds_hero.xml currently have 0); un-hide or describe the sparkline instead of `importantForAccessibility="no"` (card_kpi_badge.xml:86).
3. **#8 Thorough** — Design and implement empty, focus, and disabled states for the dashboard cards (currently absent; only loading/success/partial-error exist per DashboardFragment.kt:106-130).
4. **#9 Environmentally friendly** — Reduce or stagger the 14 concurrent network fetch calls fired on cold launch (EconomicViewModel.kt:311-469).
5. **#1 Innovative / #10 As little as possible** — Reconsider the hero-card + 6-KPI-grid pattern itself during redesign rather than re-skinning it; it's a standard finance-dashboard layout with no distinguishing mechanic.
