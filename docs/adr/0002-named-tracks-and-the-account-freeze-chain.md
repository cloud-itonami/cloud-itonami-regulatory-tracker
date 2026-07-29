# ADR-0002 — named tracks, and lateness as a third state

- Status: accepted
- Date: 2026-07-29
- Upstream: `com-junkawasaki/root` ADR-2607284000 (corporate vishing fraud —
  system dynamics and interventions)

## Context

This library shipped with one chain and free-form `:subject-id` /
`:regulatory-track` fields, on the reasoning that submission-status
tracking is the same shape everywhere and only the identifiers differ.
That held for the domains it was extracted from (hygiene market access,
drone waivers) — same stages, same three evidence fields, different
subjects.

ADR-2607284000 modelled a corporate vishing case in which ¥1.179bn left a
company in a 23.3-hour window, and produced a finding that does not fit
that shape. The bank's screening loop cycled 6.25× faster than the attack
and fired 4.317 hours into the window — 18.5% in, comfortably early — and
its effective strength was still zero. Being early is not the same as
being effective, and neither had ever been measured.

The account-freeze procedure that follows such a case is genuinely a
submission-status chain: ordered stages, a public agency, human evidence
per consequential step. But it has two properties the existing chain
cannot express. Its first step races an outflow window, so *when* it
lands is part of whether it worked. And two of its steps carry statutory
minimum notice periods, so *how long* was given is a compliance fact.

## Decision

**Tracks become data.** `tracks` maps a track id to `:ordered-stages`,
`:exit-stages`, `:consequential-stages`, and optionally
`:time-critical-stages` / `:minimum-notice-days`. Every function gains a
track-taking arity; the existing arities resolve to
`:regulatory-submission`, whose spec is the previous vars verbatim.
Nothing hand-rolls a second validator — the track's chain still goes
straight to `kotoba.crm.pipeline`.

An unregistered track **fails closed** rather than falling back to the
default chain. Silent fallback would mean a typo validates against
someone else's stages and reports success.

`:regulatory-track` inside a transition map is deliberately **not** read
as the chain selector, even though it would be elegant. It is documented
free-form caller data and existing callers pass `:india-cdsco` and
friends; promoting it would hand every one of them an
`:unknown-regulatory-track` violation.

**`:jpn-account-freeze`** registers the 振り込め詐欺救済法 chain, from the
Japanese Bankers Association's published description (retrieved live
2026-07-29): `:reported → :suspension → :extinguishment-notice →
:rights-extinguished → :distribution-notice → :distribution-paid`, exits
`:suspension-lifted` / `:no-distribution`, statutory minima of 60 and 30
days on the two notice periods.

**Lateness is reported as a third state, never as a default.**
`window-verdict` returns `:inside`, `:outside`, or
`:unknown-not-measured`, from two caller-supplied hour quantities. It
never blocks a transition, and `apply-transition` stamps the verdict into
the history entry for every time-critical stage — including when the
answer is `:unknown-not-measured`.

**Notice minima are enforced, lateness is not.** A supplied
`:notice-days` below the statutory floor is `:notice-period-too-short`, a
violation, because the floor is a number the statute fixes. An absent
`:notice-days` is recorded as unmeasured, not refused.

## Consequences

The distinction between "we don't know" and "it was fine" is now
structural rather than conventional. A record that went through a
time-critical stage without measurement says so permanently, and no
combination of partial, absent, or malformed input can produce `:inside`
— there is a test that enumerates thirteen such inputs precisely because
the tempting failure is for one of them to fall through into a confident
verdict.

Lateness deliberately does not block. Refusing to record a late freeze
would delete the only evidence that it was late, which is the opposite of
what the upstream finding calls for.

This library still files nothing, has no clock, and holds no decision
authority. `window-elapsed-hours` and `window-hours` are ground truth the
caller supplies for the same reason `:filed-by` is: a value this library
computed for itself would be a self-report dressed as evidence.

Two limits worth stating. The freeze chain is modelled from the
association's public explanation of the statute, not from the statute
text — accurate enough to track status against, and the URL is recorded
so it can be re-checked rather than trusted. And a caller can still
supply an *estimated* `:window-elapsed-hours`; this library cannot tell
an estimate from a measurement, and says so in the docstring rather than
pretending the type system is doing work it isn't.
