# Evidence — EconomicDashboard (Android, View/Fragment/XML, not Compose)

Surface: dashboard fragment. `app/src/main/java/com/economic/dashboard/ui/fragments/DashboardFragment.kt` (643 lines), `app/src/main/res/layout/fragment_dashboard.xml`, includes `card_kpi_badge.xml`, `card_fed_funds_hero.xml`.

## 1. Structural

- Interactive elements: 8 distinct handler sites — btnEditDashboard click (fragment_dashboard.xml:77-78), tvFedFundsRetry click (DashboardFragment.kt:186), hero card click (DashboardFragment.kt:249), hero long-press (DashboardFragment.kt:257), per-KPI click looped ×6 (DashboardFragment.kt:479), per-KPI retry looped ×6 (DashboardFragment.kt:411), per-KPI long-press looped ×6 (DashboardFragment.kt:473), pull-to-refresh (DashboardFragment.kt:143). No RecyclerView/nav on this screen.
- Max nesting depth: 7 (SwipeRefreshLayout > NestedScrollView > LinearLayout > FrameLayout > ShimmerFrameLayout > LinearLayout > SkeletonRow).
- Repeated patterns: `card_kpi_badge` include ×6 (fragment_dashboard.xml:99,102,116,119,133,136); `MetricGroupHeader` style ×3 (92,109,126); `SkeletonRow`×3 containing 2 SkeletonCards each (162,166,170); `PressFeedback.attach` applied to hero+6 cards (DashboardFragment.kt:172,194); `wireCardLongPress` applied to hero+6 cards (257,473).
- Dead imports: 0 confirmed (manual grep, not compiler-verified).

## 2. Visual (INFERRED — read from source, no running instance)

- Spacing scale used in fragment_dashboard.xml: 8dp, 12dp, 16dp (dimens.xml:27-39) — 3 steps visible in this file; token set defines more (2/4/8/12/16/20dp).
- Type scale (dimens.xml:6-17): 11, 13, 15, 18, 22, 28, 34, 48sp + 24sp autosize floor — 9 distinct steps app-wide; only 11sp (text_caption) referenced directly in this file.
- Distinct colors referenced directly in fragment_dashboard.xml: 3 (bg_warm_off_white, text_secondary, accent_gold) out of ~85 tokens defined app-wide; included cards pull more.
- Lowest contrast on primary text (computed): text_secondary #666666 on bg_warm_off_white #F5F3EF = 5.18:1 (AA pass, AAA fail). Card text on navy generally 5.5–15.8:1.
- States: Loading present (ShimmerFrameLayout, fragment_dashboard.xml:144-177). Success present (contentContainer reveal). Error present, partial (per-card retry chip, DashboardFragment.kt:124-130). Empty — not present (cache-first design skips it, DashboardFragment.kt:106-108 comment). Focus — no state_focused selectors found. Disabled — not present in this file.

## 3. Copy & Honesty

- Full string list: see agent reports (customize hint, "Edit cards", 3 section headers, "FED FUNDS RATE", "View history →", "Couldn't refresh · tap to retry", "Next FOMC: ...", "Cut/Hike cycle ongoing"/"Rates on hold", per-KPI labels from DashboardIndicatorCatalog.kt:33-45).
- Inflations: none found.
- Dark patterns: none found.
- Jargon: "CORE PCE INDEX" (DashboardIndicatorCatalog.kt:38), "10Y-3M SPREAD" (:36), "Cut cycle ongoing"/"Hike cycle ongoing" (DashboardFragment.kt:531-532), "Next FOMC" (:556,562) — all finance jargon without plain-language fallback.
- **Label→behavior mismatch**: "View history →" (card_fed_funds_hero.xml:73) implies a history view opens on tap, but the wired click listener (DashboardFragment.kt:249-253) opens `MetricPopupController.show`, a benchmark/status popup — not history.

## 4. Weight & Friction

- App size: debug APK 9.0MB (unminified proxy, not release size). 23 dependency declarations in build.gradle.
- Network requests on cold-launch init: 14 distinct fetch calls fire together (EconomicViewModel.kt:311-469) — Fed Funds, 2x more FRED series, PCE, Housing, MBS, Treasury, GDP, Employment, CPI, Wages, S&P500, Nasdaq, VIX, BAA spread, HY spread.
- Time-to-interactive: not measured (no running instance).
- Idle animation: 1 infinite pulse (AlphaAnimation, DashboardFragment.kt:336-340, INFINITE repeat) while loading; 4 other one-shot tweens gated by MotionUtil.animationsEnabled.
- Notification/modal on initial load: none unconditional — Toast/Snackbar/Dialog all gated behind user action or failure event. Badges (news/analyst) checked every onResume but suppressed on first run.

## 5. Accessibility

- Contrast: mostly passes AA; text_slate on navy (5.54:1) and text_secondary on off-white (5.18:1) pass AA normal but fail AAA. Two pill/drawable-background text colors not resolvable from source.
- Focus order: XML declared order is tvCacheAsOf → btnEditDashboard → hero → 6 KPI cards in fixed slots, but DashboardFragment.kt:284-318 `applyCategoryOrder()` can reorder cards at runtime — declared order does not guarantee actual traversal order.
- Keyboard reachability: every interactive element has `focusable="true"` but zero explicit key-event handlers found anywhere in the file set.
- contentDescription/semantics count: **0** across fragment_dashboard.xml, card_kpi_badge.xml, card_fed_funds_hero.xml. Sparkline is explicitly `importantForAccessibility="no"` (card_kpi_badge.xml:86), hiding it from screen readers rather than describing it.
- Skip-link: not applicable to Android.
