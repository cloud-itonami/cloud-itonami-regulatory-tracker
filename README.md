# cloud-itonami/cloud-itonami-regulatory-tracker

Portable `.cljc` technical commons for any regulatory/compliance
submission-tracking domain, not hygiene-specific — the shared piece
this fleet's `cloud-itonami-*` actors were each starting to reinvent as
their own bespoke, one-shot "block this regulatory/certification
decision" governor check, with no shared abstraction between them.

- `cloud-itonami.regulatory-tracker.core` — STATUS TRACKING ONLY for a
  regulatory/certification submission: a closed
  `:draft → :counsel-review → :submitted → :agency-review →
  :approved | :rejected | :withdrawn` stage chain, built directly on
  [`kotoba-lang/crm`](https://github.com/kotoba-lang/crm)'s
  `kotoba.crm.pipeline` ordered-stage/exit-stage engine (no parallel
  hand-rolled stage-validator here), plus a ground-truth
  human-evidence gate (`:filed-by`/`:filing-date`/`:agency-reference`,
  never defaulted or auto-generated) for any consequential transition
  (into `:submitted`/`:approved`/`:rejected`). Files nothing with any
  real government/regulatory system — no HTTP client, no
  auto-generated filing, just a pure function library a calling
  actor's own governor can invoke directly and decide its own
  HOLD/escalate/auto-commit policy around, exactly the way
  `kotoba.crm.pipeline` itself is consumed. **This is a plain library,
  not a governed actor** — no advisor, no governor, no StateGraph, no
  audit ledger of its own.

## Why this exists

Multiple `cloud-itonami-isic-*` actors in this fleet (e.g.
`cloud-itonami-isic-2023`, `cloud-itonami-isic-2029`) each carry their
own independent, one-shot "certification-decision-blocked" style
governor check with no shared engine underneath. Separately,
[`cloud-itonami-hygiene-access`](https://github.com/cloud-itonami/cloud-itonami-hygiene-access)
— a real, shipped actor — built its own bespoke stateful
submission-status tracker, `hygaccess.regulatory`
(`docs/adr/0003-mes-regulatory-sales-extensions.md` Decision 2): the
exact same `:draft → :counsel-review → :submitted → :agency-review →
:approved | :rejected | :withdrawn` chain, gated by the exact same
three-field human-evidence discipline, but scoped narrowly to a
`(market, product-type)` pair and never published as a reusable
library.

This repo extracts that reference implementation's stage chain, exit
states, and evidence-field discipline into a genuinely
domain-agnostic library — generalizing `hygaccess.regulatory`'s
implicit `(market, product-type)` subject into a free-form
`:subject-id` (a market+product-type pair, a facility certification, a
vehicle homologation, whatever the CALLING actor's own domain is) and
its dossier target into a free-form `:regulatory-track` keyword (e.g.
`:india-cdsco`, `:gcc-gso`, `:asean-cosmetic`, or anything else a
caller defines) — while keeping the same three-field evidence shape
and the same two HARD-check surface, because consistency with the
proven reference implementation matters more than inventing something
different.

**First real consumer**: `cloud-itonami-hygiene-access` is expected to
migrate its own `hygaccess.regulatory` onto this library in a later,
separate phase (out of scope for this repo's own initial build — this
repo does not itself modify that actor). **Plausible future
adopters**, for their own certification-decision-blocked-style checks:
`cloud-itonami-isic-2023` and `cloud-itonami-isic-2029` — name-checked
here as candidates this library is designed to serve, not as a
migration this repo performs now.

## Public API

```clojure
(require '[cloud-itonami.regulatory-tracker.core :as reg])

reg/ordered-stages          ;; [:draft :counsel-review :submitted :agency-review :approved]
reg/exit-stages               ;; #{:rejected :withdrawn}
reg/consequential-stages     ;; #{:submitted :approved :rejected}
reg/evidence-keys            ;; [:filed-by :filing-date :agency-reference]

(reg/valid-transition? from to)              ;; => bool
(reg/next-stages from)                       ;; => set of legal next stages
(reg/terminal-stage? stage)                  ;; => bool

(reg/evidence-complete? value)               ;; => bool, checks all 3 evidence-keys non-blank

(reg/transition-violations current-stage to-stage evidence)
;; => [] | [{:rule :regulatory-transition-invalid | :regulatory-evidence-missing :detail "..."} ...]

(reg/apply-transition existing transition)
;; existing   := nil | {:status .. :history [..] ...}   (the caller's own ground-truth record)
;; transition := {:submission-id .. :subject-id .. :regulatory-track ..
;;                :to-stage .. :filed-by .. :filing-date .. :agency-reference ..}
;; => {:ok? true  :record <new-record>}
;;  | {:ok? false :violations [..] :record existing}   ;; UNCHANGED on any violation
```

`apply-transition` is the only function that produces a new record; it
is pure and side-effect-free — persisting the returned `:record` is the
caller's own responsibility, mirroring `kotoba.crm.pipeline`'s
storage-agnostic posture.

## Deviation from `hygaccess.regulatory`'s reference chain

`hygaccess.regulatory`'s own hand-written transition table fans
`:agency-review` out to all three of
`:approved`/`:rejected`/`:withdrawn`, and forbids reaching any of the
three from an earlier stage. Reproducing that literally by putting
`:agency-review` last in `ordered-stages` does not work with
`kotoba.crm.pipeline`'s ACTUAL semantics (verified empirically, not
assumed): `kotoba.crm.pipeline/terminal-stages` unconditionally treats
the last `ordered-stages` entry as terminal — no outgoing transition
at all, exit-stages included — so `:agency-review` would itself become
terminal and block every transition out of it, `:approved` included.

Instead, this library puts the SUCCESS outcome, `:approved`, as the
last ordered stage — `:agency-review -> :approved` is then an ordinary
immediate-next-stage transition, reachable **only** by walking the
full chain, exactly matching the reference implementation for that
path — and narrows `exit-stages` to the two ABANDONMENT outcomes,
`:rejected` and `:withdrawn`. Per `kotoba.crm.pipeline`'s own
documented semantics, an exit stage is reachable from **any**
non-terminal stage (see `kotoba-lang/crm`'s own README: "exit stages …
reachable from any non-terminal stage"), so this library allows
rejecting or withdrawing a submission while still `:draft`, where the
reference implementation would have held that transition invalid.
`:approved` is unaffected by this generalization.

This is the deliberate, disclosed cost of building on the shared engine
rather than hand-rolling a second, stricter parallel validator — and
arguably the more broadly reusable default for a library serving
submission domains beyond hygiene's own specific process (a submission
genuinely can be rejected by counsel or withdrawn by its own filer
early, without ever reaching formal agency review). A caller whose own
regulatory track genuinely needs the stricter "exit only from
`:agency-review`" rule can layer that as an *additional* check in its
own governor; this library does not preclude a caller being stricter
than it is.

## Scope (deliberately narrow)

- STATUS TRACKING ONLY — no HTTP client, no real government/regulatory
  filing capability, ever.
- Evidence is a flat three-field shape
  (`:filed-by`/`:filing-date`/`:agency-reference`), never defaulted or
  auto-generated by this library. A caller that wants a different
  evidence shape is out of scope for this build; open an issue/ADR if a
  future consumer needs to generalize evidence fields too.
- No independent decision authority: no HOLD/escalate/auto-commit
  policy, no confidence gate, no audit ledger. The calling actor's own
  governor owns all of that — this library only ever answers "is this
  transition/evidence valid" and "what would the resulting record look
  like."

## Test

```bash
clojure -M:test
clojure -M:lint
```

## License

AGPL-3.0-or-later (matches `cloud-itonami-hygiene-access`, its first
intended real-world consumer).
