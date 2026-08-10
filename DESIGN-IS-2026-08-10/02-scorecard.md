# Scorecard — EconomicDashboard

1. Good design is innovative — Score: 1/3
   Evidence: hero card + 6-KPI grid, cycle-status text (01-evidence.md §1,§3) — standard finance-dashboard pattern, no novel mechanic.
   Justification: imitates the category with minor touches (cycle status strings), no advance on the form.

2. Good design makes a product useful — Score: 1/3
   Evidence: 0 contentDescription/semantics across all 3 layout files; sparkline explicitly hidden from accessibility tree (card_kpi_badge.xml:86) (01-evidence.md §5).
   Justification: sighted users complete the task in one screen, but screen-reader users — part of "general public" — cannot identify or read any KPI card; worst-instance scoring applies.

3. Good design is aesthetic — Score: 2/3
   Evidence: consistent 8/12/16dp spacing and token-based colors in audited file, no violations found (01-evidence.md §2).
   Justification: no inconsistencies surfaced, but evidence only covers one screen file — capped below 3 given incomplete visual coverage of included cards.

4. Good design makes a product understandable — Score: 1/3
   Evidence: "CORE PCE INDEX," "10Y-3M SPREAD," "Cut cycle ongoing" jargon (DashboardIndicatorCatalog.kt:36,38; DashboardFragment.kt:531-532); "View history →" mismatch (card_fed_funds_hero.xml:73 vs DashboardFragment.kt:249-253) (01-evidence.md §3).
   Justification: 2-3 controls/labels unclear to a first-time general-audience user, plus unexplained finance jargon throughout.

5. Good design is unobtrusive — Score: 2/3
   Evidence: one INFINITE-repeat pulse animation during loading (DashboardFragment.kt:336-340) (01-evidence.md §4).
   Justification: chrome stays quiet outside the loading state; the infinite pulse is the one attention-competing element, scoped to loading only.

6. Good design is honest — Score: 1/3
   Evidence: "View history →" label wired to a benchmark popup, not history (card_fed_funds_hero.xml:73; DashboardFragment.kt:249-253) (01-evidence.md §3).
   Justification: a functional claim-to-behavior mismatch is more serious than a wording inflation — one confirmed case, no inflations/dark patterns otherwise.

7. Good design is long-lasting — Score: 2/3
   Evidence: card-grid dashboard pattern, token-driven type/spacing/color, no skeuomorphic or fad markers found (01-evidence.md §1,§2).
   Justification: no dated trend markers surfaced, but visual coverage is partial so held below 3 per tie-breaker rule.

8. Good design is thorough down to the last detail — Score: 1/3
   Evidence: states checklist — loading✓, success✓, error (partial)✓, empty✗, focus✗, disabled✗ (01-evidence.md §2).
   Justification: 3 of 6 states missing or unconfirmed (empty, focus, disabled).

9. Good design is environmentally friendly — Score: 2/3
   Evidence: 14 concurrent network fetch calls fire on cold launch (EconomicViewModel.kt:311-469); animations gated behind MotionUtil.animationsEnabled (01-evidence.md §4).
   Justification: startup request fan-out is heavy, but motion is explicitly gated rather than always-on, keeping this out of the bottom tier.

10. Good design is as little design as possible — Score: 2/3
    Evidence: card_kpi_badge reused 6x, MetricGroupHeader reused 3x cleanly (01-evidence.md §1); "View history →" affordance points nowhere real (§3).
    Justification: strong component reuse overall, but at least one element ("View history") doesn't earn its literal claim — a removable/fixable affordance.

**Total: 15/30**
