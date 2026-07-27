#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# check_badges.sh — R2-TICKET-10 regression guard.
#
# Round 1 of the badge work centralised the badge and it partly came back within
# a day: a new screen hand-wrote its own dot + word row, picked its own grey, and
# the three-greys problem returned. This script is what stops round 3.
#
# It fails the build if a status badge is styled anywhere other than
# @style/Badge.Status + layout/view_status_badge.xml:
#
#   1. a status/tier TextView in res/layout declares android:textColor,
#      android:textSize, android:textStyle, android:textAllCaps or
#      android:letterSpacing inline;
#   2. a fragment calls setTextColor() on a tier/status TextView;
#   3. a layout hand-writes a dot + status row instead of <include>ing
#      layout/view_status_badge.xml.
#
# Run manually:      ./scripts/check_badges.sh
# Runs automatically: wired into the `check` task in app/build.gradle.
#
# If you are adding a badge to a new screen, the fix is never to loosen this
# script — it is one <include layout="@layout/view_status_badge"> plus one
# BenchmarkStyle.applyBadge(...) call. See view_status_badge.xml.
# ═══════════════════════════════════════════════════════════════════════════════
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAYOUTS="$ROOT/app/src/main/res/layout"
FRAGMENTS="$ROOT/app/src/main/java/com/economic/dashboard/ui/fragments"
BADGE_LAYOUT="$LAYOUTS/view_status_badge.xml"

fail=0
note() { printf '  %s\n' "$1"; }

# ── 1. Inline text styling on a status TextView ───────────────────────────────
# A status word is a `tv…` id ending in Status/Tier (tvBaaStatus, tvTierStatus)
# or in Text preceded by Status/Indicator (tvGdpStatusText, tvIndicator3MText).
# Two things this deliberately does NOT match, both of which legitimately carry
# their own textColor: container ids like `cardPceStatus` (the trailing-quote
# anchor and the `tv` prefix exclude them) and unrelated copy like
# `tvPromptText`. view_status_badge.xml is the one legal home for badge styling.
hits="$(grep -rn --include='*.xml' \
        -E 'android:id="@\+id/tv[A-Za-z0-9]*((Status|Tier)|(Status|Indicator)[A-Za-z0-9]*Text)"' \
        "$LAYOUTS" 2>/dev/null \
        | grep -v 'view_status_badge.xml' || true)"

if [ -n "$hits" ]; then
    # Re-read each offending file and check the attribute block around the id.
    while IFS=: read -r file line _rest; do
        [ -n "${file:-}" ] || continue
        block="$(awk -v s="$line" 'NR>=s-8 && NR<=s+10' "$file")"
        if printf '%s' "$block" | grep -qE 'android:(textColor|textSize|textStyle|textAllCaps|letterSpacing)'; then
            if [ $fail -eq 0 ]; then
                echo "FAIL: status badge styled outside @style/Badge.Status"
            fi
            note "$file:$line declares text styling inline on a status TextView."
            fail=1
        fi
    done <<< "$hits"
fi

# ── 2. setTextColor on a tier/status view in a fragment ───────────────────────
if [ -d "$FRAGMENTS" ]; then
    java_hits="$(grep -rnE '(tv|view)[A-Za-z0-9]*(Status|Tier|Indicator)[A-Za-z0-9]*\.setTextColor' \
                 "$FRAGMENTS" 2>/dev/null || true)"
    if [ -n "$java_hits" ]; then
        echo "FAIL: a fragment sets a status badge colour in Java"
        printf '%s\n' "$java_hits" | sed 's/^/  /'
        note "Delete the call — @style/Badge.Status owns the badge colour."
        fail=1
    fi
fi

# ── 3. Hand-written dot + status rows ────────────────────────────────────────
# The dot is always @dimen/dot_status. If a layout sizes a View with it without
# going through the shared include, someone rebuilt the badge row by hand.
dot_hits="$(grep -rln --include='*.xml' 'dot_status' "$LAYOUTS" 2>/dev/null || true)"
for f in $dot_hits; do
    [ "$f" = "$BADGE_LAYOUT" ] && continue
    echo "FAIL: hand-written status dot"
    note "$f sizes a dot with @dimen/dot_status directly."
    note "Use <include layout=\"@layout/view_status_badge\" android:id=\"@+id/statusXxx\" /> instead."
    fail=1
done

if [ $fail -eq 0 ]; then
    echo "check_badges: OK — every status badge goes through @style/Badge.Status."
fi
exit $fail
