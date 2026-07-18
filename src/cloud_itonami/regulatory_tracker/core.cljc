(ns cloud-itonami.regulatory-tracker.core
  "Generic regulatory/compliance SUBMISSION-STATUS TRACKING library for
  any `cloud-itonami` actor that needs to track the REAL-WORLD status of
  a regulatory/certification dossier against some subject it owns.

  Extracted from `cloud-itonami-hygiene-access`'s own bespoke
  `hygaccess.regulatory` namespace
  (docs/adr/0003-mes-regulatory-sales-extensions.md Decision 2), which
  independently reinvented the same 'block this regulatory/
  certification decision' stage-tracker every `cloud-itonami-isic-*`
  actor was starting to grow its own copy of. This library generalizes
  that reference implementation so the two identifying fields of a
  submission are both free-form, not a closed hygiene-specific set:

  - `:subject-id`      -- what this submission is ABOUT. In
                          `hygaccess.regulatory` this was implicitly a
                          (market, product-type) pair baked into the
                          record id string (e.g.
                          \"REG-IN-water-purification-drops\"); here it
                          is any caller-defined identifier -- a
                          market+product-type pair, a facility
                          certification, a vehicle homologation, a
                          software conformance filing, whatever the
                          CALLING actor's own domain is. This library
                          never inspects or interprets `:subject-id` --
                          it is opaque data as far as this namespace is
                          concerned.
  - `:regulatory-track` -- WHICH regulatory body/dossier this
                          submission targets, e.g. `:india-cdsco`,
                          `:gcc-gso`, `:asean-cosmetic` (the three
                          `hygaccess.regulatory` reference dossiers),
                          or any other caller-defined keyword. Also
                          opaque to this library.

  STATUS TRACKING ONLY -- exactly like the reference implementation,
  this namespace files nothing with any real government/regulatory
  system, has no HTTP client, and never auto-generates or defaults the
  human-evidence fields a consequential transition requires.

  This is a PLAIN FUNCTION LIBRARY with NO independent decision
  authority of its own -- not a governor, not an advisor, not a
  StateGraph, no ledger. The CALLING actor's own governor is expected
  to invoke `valid-transition?`/`evidence-complete?`/
  `transition-violations`/`apply-transition` directly and decide its
  own HOLD/escalate/auto-commit policy around the result, mirroring
  exactly how `kotoba.crm.pipeline` itself is consumed by its own
  callers (see `kotoba-lang/crm`'s README: a plain function library,
  no independent decision authority).

  Built ON TOP of `kotoba.crm.pipeline`'s generic ordered-stage/
  exit-stage transition engine -- this namespace does NOT hand-roll a
  parallel stage-validator.

  ONE documented, deliberate generalization from the
  `hygaccess.regulatory` reference, forced by `kotoba.crm.pipeline`'s
  own actual semantics (verified empirically against its real
  behavior, not assumed): `kotoba.crm.pipeline/terminal-stages`
  unconditionally treats the LAST `ordered-stages` entry as terminal
  (no outgoing transition at all, not even to an exit-stage) --
  `(last ordered-stages)` is meant to be the pipeline's own 'reached
  the end, successfully' state (e.g. a CRM `:closed-won`), with
  `exit-stages` as an alternate ABANDONMENT path reachable from any
  earlier, still-non-terminal stage (`kotoba-lang/crm`'s own README:
  'exit stages ... reachable from any non-terminal stage'). Putting
  `:agency-review` last (as `hygaccess.regulatory`'s own hand-written
  transition table effectively does, fanning `:agency-review` out to
  all three of `:approved`/`:rejected`/`:withdrawn`) would make
  `:agency-review` itself terminal under `kotoba.crm.pipeline` and
  block EVERY transition out of it, `:approved` included -- silently
  breaking the one transition this whole chain exists to allow. So
  this library instead puts the SUCCESS outcome, `:approved`, as the
  last ordered stage (`:agency-review -> :approved` is then the
  ordinary immediate-next-stage transition, reachable ONLY by walking
  the full chain, exactly matching the reference implementation for
  that path) and narrows `exit-stages` to the two ABANDONMENT outcomes,
  `:rejected` and `:withdrawn`, which `kotoba.crm.pipeline` makes
  reachable from any non-terminal stage rather than only from
  `:agency-review` as the reference implementation's hand-written table
  did. Concretely: this library allows rejecting or withdrawing a
  submission while still `:draft` (before ever reaching counsel
  review), where the reference implementation would have HELD that
  transition as invalid; `:approved` remains reachable only by walking
  the full `:draft -> :counsel-review -> :submitted -> :agency-review
  -> :approved` chain, identically to the reference. This is the
  sanctioned cost of reuse -- not hand-rolling a second, stricter
  parallel validator alongside `kotoba.crm.pipeline` -- and is
  arguably the more broadly reusable default for a shared library
  serving submission domains beyond hygiene's own specific process (a
  submission genuinely CAN be rejected by counsel or withdrawn by its
  own filer early, without ever reaching formal agency review). A
  caller whose own regulatory track genuinely needs the stricter 'exit
  only from `:agency-review`' rule can layer that ADDITIONAL check in
  its own governor (this library does not preclude a caller from being
  stricter than it is -- it only declines to hand-roll that strictness
  itself)."
  (:require [clojure.string :as str]
            [kotoba.crm.pipeline :as pipeline]))

;; ----------------------------- SubmissionRecord shape -----------------------------
;;
;; {:submission-id     <string, caller-assigned id>
;;  :subject-id        <free-form -- what this submission is about>
;;  :regulatory-track  <free-form keyword -- which regulatory body/dossier>
;;  :status            <current stage -- one of `ordered-stages` or `exit-stages`>
;;  :filed-by          <string, present once a consequential transition has occurred>
;;  :filing-date       <string, ditto>
;;  :agency-reference  <string, ditto>
;;  :history           [{:from .. :to .. :evidence {..}} ...] -- one entry
;;                      per successfully applied transition, oldest first}
;;
;; This is documentation only (an EDN map shape), not a schema/spec
;; dependency -- matching `kotoba.crm.pipeline`'s own storage-agnostic,
;; dependency-free posture.

;; ----------------------------- stage chain -----------------------------

(def ordered-stages
  "The linear regulatory-submission stage chain, ending in the SUCCESS
  outcome `:approved` (kotoba.crm.pipeline's own convention: the last
  ordered stage is the pipeline's terminal 'reached the end,
  successfully' state) -- :draft -> :counsel-review -> :submitted ->
  :agency-review -> :approved. `:agency-review -> :approved` is an
  ordinary immediate-next-stage transition, reachable ONLY by walking
  the full chain, matching `hygaccess.regulatory/transitions`'s own
  `:agency-review` -> `:approved` path exactly."
  [:draft :counsel-review :submitted :agency-review :approved])

(def exit-stages
  "The two ABANDONMENT outcomes -- reachable from any non-terminal
  stage per `kotoba.crm.pipeline`'s own semantics (see ns docstring for
  why `:approved` is deliberately NOT in this set, unlike
  `hygaccess.regulatory/terminal-statuses`, which groups all three
  decision outcomes together)."
  #{:rejected :withdrawn})

(defn valid-transition?
  "Is `from` -> `to` an allowed single-step transition? Delegates
  entirely to `kotoba.crm.pipeline/valid-transition?` over this
  namespace's own `ordered-stages`/`exit-stages` -- no parallel
  stage-validator. See the ns docstring for the one place this differs
  from `hygaccess.regulatory`'s own stricter reference chain
  (`:rejected`/`:withdrawn` reachable from any non-terminal stage
  here, vs. only from `:agency-review` there; `:approved` matches the
  reference exactly, reachable only via the full chain)."
  [from to]
  (pipeline/valid-transition? ordered-stages exit-stages from to))

(defn next-stages
  "All stages `from` may legally transition to next (the immediate next
  ordered stage plus every exit stage), or an empty set if `from` is
  already terminal."
  [from]
  (pipeline/next-stages ordered-stages exit-stages from))

(defn terminal-stage?
  "Is `stage` terminal (accepts no further transition at all)?"
  [stage]
  (contains? (pipeline/terminal-stages ordered-stages exit-stages) stage))

;; ----------------------------- human-evidence discipline -----------------------------

(def consequential-stages
  "Transitions INTO these three stages require independently-supplied
  human evidence -- matches `hygaccess.regulatory/consequential-
  statuses` exactly."
  #{:submitted :approved :rejected})

(def evidence-keys
  "The three ground-truth evidence fields a human filer/counsel must
  independently supply for any consequential transition -- matches
  `hygaccess.regulatory/evidence-keys` exactly (kept as the same
  three-field shape deliberately: consistency with the reference
  implementation matters more than inventing a different field set).
  NEVER defaulted or auto-generated by this library or by any caller
  that wants to preserve the 'ground truth, not self-report' discipline
  this fleet uses throughout."
  [:filed-by :filing-date :agency-reference])

(defn- non-blank-string? [v]
  (and (string? v) (not (str/blank? v))))

(defn evidence-complete?
  "Ground-truth check: for a transition INTO a consequential stage, are
  ALL THREE evidence fields present as non-blank strings in `value` (a
  proposal's own `:value` map, or any map carrying the same keys)?
  Never defaulted -- an absent, nil, or blank/whitespace-only field is
  treated as missing evidence, not silently synthesized."
  [value]
  (every? #(non-blank-string? (get value %)) evidence-keys))

;; ----------------------------- pure transition validation -----------------------------

(defn transition-violations
  "Pure predicate a calling actor's own governor should invoke BEFORE
  ever calling `apply-transition` -- mirrors
  `hygaccess.governor/regulatory-transition-invalid-violations` +
  `regulatory-evidence-missing-violations`, generalized to this
  library's own free-form subject/track shape. Returns a vector of
  `{:rule .. :detail ..}` maps, empty when the transition is clean. Two
  possible rules, exactly matching the reference implementation's own
  two HARD checks:

    `:regulatory-transition-invalid` -- `to-stage` is not a valid
       single-step transition from `current-stage` (no skipping
       stages, never taken on the caller's own claim).
    `:regulatory-evidence-missing` -- `to-stage` is consequential and
       `evidence` does not carry all three non-blank `evidence-keys`.

  `current-stage` -- the submission's own CURRENTLY STORED stage (or
  `:draft` if no record exists yet for this subject) -- the CALLER is
  responsible for looking this up from its own ground-truth store,
  never trusting a proposal's own self-reported 'current stage'.
  `to-stage`      -- the proposal's own claimed destination stage.
  `evidence`      -- the proposal's own `:value` map (or a submap of
                     it) carrying whichever of `evidence-keys` it
                     claims to supply."
  [current-stage to-stage evidence]
  (into []
        (concat
         (when (or (nil? to-stage) (not (valid-transition? current-stage to-stage)))
           [{:rule :regulatory-transition-invalid
             :detail (str current-stage " -> " (pr-str to-stage)
                          " is not an allowed single-step transition (allowed: "
                          (pr-str (next-stages current-stage)) ")")}])
         (when (and (contains? consequential-stages to-stage)
                    (not (evidence-complete? evidence)))
           [{:rule :regulatory-evidence-missing
             :detail (str "transition into " to-stage " requires all of " (pr-str evidence-keys)
                          " as independently human-supplied, non-blank strings"
                          " -- never defaulted or auto-generated")}]))))

;; ----------------------------- pure transition application -----------------------------

(defn apply-transition
  "Pure state-transition function -- the only function in this
  namespace that produces a new record. `existing` is the submission's
  own CURRENTLY STORED record (a map with at least `:status`) or `nil`
  for a brand-new submission (treated as currently `:draft` with no
  history). `transition` is:

    {:submission-id .. :subject-id .. :regulatory-track ..
     :to-stage .. :filed-by .. :filing-date .. :agency-reference ..}

  (`:submission-id`/`:subject-id`/`:regulatory-track` only matter when
  `existing` is `nil` -- they seed the new record; evidence keys are
  optional unless `:to-stage` is consequential.)

  Returns `{:ok? true :record <new-record>}` when the transition and
  evidence are both valid, or `{:ok? false :violations [..] :record
  existing}` (the record UNCHANGED, exactly as passed in -- possibly
  `nil`) otherwise. This function never partially applies an invalid
  transition, and it does NOT itself decide HOLD/escalate/auto-commit
  policy (e.g. whether even a clean transition still requires human
  approval before landing) -- that decision belongs entirely to the
  calling actor's own governor/phase table, mirroring
  `hygaccess.regulatory`'s own scope: STATUS TRACKING ONLY, no
  independent decision authority."
  [existing {:keys [submission-id subject-id regulatory-track to-stage] :as transition}]
  (let [current-stage (:status existing :draft)
        evidence (select-keys transition evidence-keys)
        violations (transition-violations current-stage to-stage evidence)]
    (if (seq violations)
      {:ok? false :violations violations :record existing}
      {:ok? true
       :record (-> (or existing
                       {:submission-id submission-id
                        :subject-id subject-id
                        :regulatory-track regulatory-track
                        :history []})
                   (update :history (fnil conj [])
                           {:from current-stage :to to-stage :evidence evidence})
                   (assoc :status to-stage)
                   (merge evidence))})))
