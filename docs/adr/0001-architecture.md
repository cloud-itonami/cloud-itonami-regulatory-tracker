# ADR-0001: extract a shared regulatory-submission-status tracker on top of `kotoba.crm.pipeline`

## Status

Accepted. `cloud-itonami-regulatory-tracker` created directly as a
standalone, reusable library — not a governed actor.

## Context

Multiple `cloud-itonami-isic-*` actors in this fleet (e.g.
`cloud-itonami-isic-2023`, `cloud-itonami-isic-2029`) each independently
carry a one-shot "block this regulatory/certification decision"
governor check with no shared abstraction underneath. Separately,
`cloud-itonami-hygiene-access` — a real, shipped actor — built its own
bespoke stateful submission-status tracker,
`hygaccess.regulatory` (`docs/adr/0003-mes-regulatory-sales-extensions.md`
Decision 2 in that repo): a closed
`:draft → :counsel-review → :submitted → :agency-review →
:approved | :rejected | :withdrawn` state machine per `(market,
product-type)` pair, gated by a three-field human-evidence discipline
(`:filed-by`/`:filing-date`/`:agency-reference`, never defaulted or
auto-generated) for any consequential transition.

The human owner pointed out this concern should be a shared, reusable
`cloud-itonami` library rather than bespoke-per-actor. Investigation
confirmed:

1. No existing shared regulatory-submission-tracking library exists
   anywhere in this fleet.
2. [`kotoba-lang/crm`](https://github.com/kotoba-lang/crm)'s
   `kotoba.crm.pipeline` — a generic ordered-stage/exit-stage
   transition validator, already consumed by three real
   `cloud-itonami` actors (`cloud-itonami-isic-5820`,
   `cloud-itonami-isic-6201`, `cloud-itonami-isic-6202`) via a
   `:local/root "../../kotoba-lang/crm"` sibling-checkout dependency —
   is exactly the right underlying engine for the stage-transition
   half of this problem. Hand-rolling a parallel stage-validator
   instead of reusing it would duplicate exactly the abstraction this
   extraction is meant to stop duplicating.

## Decision

Build `cloud-itonami.regulatory-tracker.core`, a portable `.cljc`
library, ON TOP of `kotoba.crm.pipeline`:

- `ordered-stages` = `[:draft :counsel-review :submitted
  :agency-review :approved]` (ending in the SUCCESS outcome, per
  `kotoba.crm.pipeline`'s own convention — see Deviation below),
  `exit-stages` = `#{:rejected :withdrawn}` (the two ABANDONMENT
  outcomes) — together reproducing `hygaccess.regulatory`'s own
  `:draft → :counsel-review → :submitted → :agency-review → :approved`
  path and its three terminal statuses
  (`{:approved :rejected :withdrawn}`) exactly.
- `valid-transition?`/`next-stages`/`terminal-stage?` delegate entirely
  to `kotoba.crm.pipeline/valid-transition?`/`next-stages`/
  `terminal-stages` over those two definitions — no parallel
  hand-rolled validator.
- A `SubmissionRecord` shape generalizing `hygaccess.regulatory`'s
  implicit `(market, product-type)` subject into a free-form
  `:subject-id` (opaque to this library — a market+product-type pair,
  a facility certification, a vehicle homologation, whatever the
  calling actor's own domain is) and a free-form `:regulatory-track`
  keyword (which regulatory body/dossier — `:india-cdsco`, `:gcc-gso`,
  `:asean-cosmetic`, or any other caller-defined keyword, not a closed
  hygiene-specific set).
- `evidence-keys` = `[:filed-by :filing-date :agency-reference]` and
  `evidence-complete?`, kept as the exact same three-field shape as
  `hygaccess.regulatory/evidence-keys` — consistency with the proven
  reference implementation matters more than inventing a different
  shape.
- `transition-violations` and `apply-transition` as the pure functions
  a consuming actor's own governor calls directly — this library holds
  no independent decision authority (no HOLD/escalate/auto-commit
  policy, no confidence gate, no audit ledger), exactly the way
  `kotoba.crm.pipeline` itself is consumed by its own three callers.

### Deviation from the reference implementation

`hygaccess.regulatory`'s own hand-written transition table fans
`:agency-review` out to all three of
`:approved`/`:rejected`/`:withdrawn`. Reproducing that literally by
putting `:agency-review` last in `ordered-stages` was tried first and
FAILED empirically (a real test run, not a hypothesis): `kotoba.crm.
pipeline/terminal-stages` unconditionally treats the last
`ordered-stages` entry as terminal, so `:agency-review` would itself
become terminal and block every transition out of it — including
`:agency-review -> :approved`, the one transition this whole chain
exists to allow.

The corrected design instead puts the SUCCESS outcome, `:approved`, as
the last ordered stage — `:agency-review -> :approved` becomes an
ordinary immediate-next-stage transition, reachable ONLY by walking
the full chain, matching the reference implementation exactly for that
path — and narrows `exit-stages` to the two ABANDONMENT outcomes,
`:rejected` and `:withdrawn`. `kotoba.crm.pipeline/valid-transition?`'s
documented semantics make an exit stage reachable from **any**
non-terminal stage (`kotoba-lang/crm`'s own README: "exit stages …
reachable from any non-terminal stage") — matching how its other three
consumers (5820/6201/6202) already use it for "abandon a deal/lead/
ticket at any point" semantics — so this library allows rejecting or
withdrawing a submission while still `:draft`, where the reference
implementation would have held that transition invalid. `:approved` is
unaffected by this generalization. Rather than hand-roll a second,
stricter parallel validator solely to reproduce `hygaccess.regulatory`'s
tighter rule for `:rejected`/`:withdrawn`, this library defers to
`kotoba.crm.pipeline`'s own semantics and documents the difference
(README "Deviation from `hygaccess.regulatory`'s reference chain"). A
caller needing the stricter rule can layer it as an additional check in
its own governor.

### Not built

- No governor, advisor, StateGraph, or audit ledger — this is
  explicitly a plain library per the human owner's framing, consumed by
  each calling actor's own independent governor (mirroring
  `kotoba.crm.pipeline` itself, which has none of these either).
- No migration of `cloud-itonami-hygiene-access` onto this library, and
  no changes to `cloud-itonami-isic-2023`/`cloud-itonami-isic-2029` —
  both are explicitly out of scope for this build; a later, separate
  phase wires consumers.
- No generalization of the evidence-field shape beyond the reference
  implementation's three fields — a future consumer needing a different
  evidence shape should open a follow-up ADR rather than this build
  guessing at a shape with no second real consumer to validate against.

## Consequences

(+) `cloud-itonami-isic-*` actors gain a single shared engine for
regulatory/certification-decision-blocked-style checks instead of each
growing its own bespoke copy. (+) Consistent with the fleet's existing
"technical commons on top of `kotoba.crm.pipeline`" pattern
(`kotoba-lang/crm`'s `revrec`/`leadscore`/`funnel` siblings). (-) The
exit-stage-reachability deviation from `hygaccess.regulatory`'s
stricter reference means a direct swap-in migration for that actor is
not fully behavior-preserving without also deciding whether the looser
rule is acceptable for its own domain — left to the later migration
phase to resolve, not guessed here.
