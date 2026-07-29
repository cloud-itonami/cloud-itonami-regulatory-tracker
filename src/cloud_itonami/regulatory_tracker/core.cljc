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

;; ----------------------------- named tracks -----------------------------

(def default-track
  "The track every single-track arity resolves to -- the stage chain this
  library shipped with, unchanged."
  :regulatory-submission)

(def tracks
  "Registered stage chains, keyed by track id.

  A track is DATA: an `:ordered-stages` chain, an `:exit-stages` set, the
  `:consequential-stages` that demand human evidence, and -- for tracks
  where lateness is a real failure mode -- `:time-critical-stages` and
  `:minimum-notice-days`. Everything in this namespace reads a track from
  here rather than closing over module-level vars, so adding a chain does
  not mean adding a parallel validator.

  `:regulatory-submission` is the original chain and is byte-for-byte the
  same stages/exits/consequential set the single-track arities have always
  used.

  `:jpn-account-freeze` is the account-freeze and victim-redistribution
  chain of Japan's 振り込め詐欺救済法 (犯罪利用預金口座等に係る資金による被害
  回復分配金の支払等に関する法律 -- Act on Payment of Damage Recovery
  Distributions from Funds in Deposit Accounts Used for Crimes), as
  published by the Japanese Bankers Association at
  https://www.zenginkyo.or.jp/hanzai/rescure/ (retrieved 2026-07-29):

    :reported              a victim/institution has reported the account
    :suspension            取引停止措置 -- the institution suspends the account
    :extinguishment-notice the institution asks the Deposit Insurance
                           Corporation to publish the start of the
                           deposit-claim extinguishment procedure
    :rights-extinguished   失権 -- the holder's claim to the balance ends,
                           after a rights-assertion period of AT LEAST 60 days
    :distribution-notice   publication of the start of the distribution
                           application procedure
    :distribution-paid     被害回復分配金の支払 -- payment, after an
                           application period of AT LEAST 30 days

  Exits are the two abandonment outcomes: `:suspension-lifted` (the
  institution found no grounds, or the holder established the account was
  not criminally used) and `:no-distribution` (the procedure ends without
  a payment). Both are reachable from any non-terminal stage, per
  `kotoba.crm.pipeline`'s own semantics.

  The day counts are STATUTORY MINIMUMS FOR A NOTICE PERIOD, not
  deadlines: giving 90 days where the statute demands 60 is compliant,
  giving 45 is not. They are a different quantity from
  `:time-critical-stages`, which is about being LATE. Both appear on this
  track because both failure modes are real here, and conflating them
  would let one silently satisfy the other."
  {:regulatory-submission
   {:ordered-stages ordered-stages
    :exit-stages exit-stages
    :consequential-stages #{:submitted :approved :rejected}}

   :jpn-account-freeze
   {:ordered-stages [:reported :suspension :extinguishment-notice
                     :rights-extinguished :distribution-notice :distribution-paid]
    :exit-stages #{:suspension-lifted :no-distribution}
    :consequential-stages #{:suspension :rights-extinguished :distribution-paid}
    :time-critical-stages #{:suspension}
    :minimum-notice-days {:rights-extinguished 60
                          :distribution-paid 30}}})

(defn track-spec
  "The registered spec for `track`, or `nil` if it is not registered.
  Callers that resolve a track themselves should treat `nil` as
  fail-closed (refuse the transition), never as 'fall back to the default
  chain' -- a typo'd track id silently validating against a different
  chain is exactly the failure this lookup exists to prevent."
  [track]
  (get tracks track))

(defn valid-transition?
  "Is `from` -> `to` an allowed single-step transition on `track`
  (`default-track` when omitted)? Delegates entirely to
  `kotoba.crm.pipeline/valid-transition?` over the track's own
  `:ordered-stages`/`:exit-stages` -- no parallel stage-validator. An
  unregistered track is `false`: fail closed, never a silent fall back to
  the default chain.

  See the ns docstring for the one place the `:regulatory-submission`
  chain differs from `hygaccess.regulatory`'s own stricter reference chain
  (`:rejected`/`:withdrawn` reachable from any non-terminal stage here, vs.
  only from `:agency-review` there; `:approved` matches the reference
  exactly, reachable only via the full chain)."
  ([from to] (valid-transition? default-track from to))
  ([track from to]
   (if-let [{:keys [ordered-stages exit-stages]} (track-spec track)]
     (pipeline/valid-transition? ordered-stages exit-stages from to)
     false)))

(defn next-stages
  "All stages `from` may legally transition to next on `track` (the
  immediate next ordered stage plus every exit stage), or an empty set if
  `from` is already terminal. An unregistered track yields `#{}`."
  ([from] (next-stages default-track from))
  ([track from]
   (if-let [{:keys [ordered-stages exit-stages]} (track-spec track)]
     (pipeline/next-stages ordered-stages exit-stages from)
     #{})))

(defn terminal-stage?
  "Is `stage` terminal on `track` (accepts no further transition at all)?
  An unregistered track answers `true` -- with no chain to consult, no
  transition can be allowed, and `true` is the fail-closed answer."
  ([stage] (terminal-stage? default-track stage))
  ([track stage]
   (if-let [{:keys [ordered-stages exit-stages]} (track-spec track)]
     (contains? (pipeline/terminal-stages ordered-stages exit-stages) stage)
     true)))

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

;; ----------------------------- lateness and notice periods -----------------------------
;;
;; A transition can be perfectly ordered, fully evidenced, and still
;; worthless because it landed after the money left. That is not a
;; hypothetical: in the case this track was built from
;; (`com-junkawasaki/root` ADR-2607284000) the bank's screening control
;; cycled 6.25x faster than the attack and fired 4.317 hours into a
;; 23.317-hour transfer window -- comfortably "in time" -- and still
;; contributed nothing, because being early is not the same as being
;; effective and neither was ever measured.
;;
;; So this library reports lateness as a THIRD state, never as a default.
;; `:unknown-not-measured` is not a softer `:inside`; it means the record
;; carries no evidence either way, and it is what a record permanently
;; says when nobody measured. There is no clock in here -- this namespace
;; is pure and has no time capability -- so every quantity below is
;; ground truth the caller supplies, exactly like `evidence-keys`.

(def window-keys
  "Ground-truth quantities for a time-critical transition. Both are hours,
  both come from the caller, neither is ever defaulted:

    :window-elapsed-hours  how long after the window opened this stage was
                           actually entered
    :window-hours          how long the window was

  A caller that cannot supply both should supply neither and accept
  `:unknown-not-measured` rather than estimate. An estimated
  `:window-elapsed-hours` produces a confident verdict from a guess, which
  is worse than no verdict."
  [:window-elapsed-hours :window-hours])

(def notice-keys
  "Ground-truth quantity for a stage carrying a statutory minimum notice
  period: `:notice-days`, the number of days actually given."
  [:notice-days])

(defn- finite-number? [v]
  (and (number? v)
       (= v v)                                        ; NaN fails this
       (> v ##-Inf)
       (< v ##Inf)))

(defn window-verdict
  "Did a time-critical transition land inside its window?

    `:inside`               elapsed < window, both measured
    `:outside`              elapsed >= window, both measured
    `:unknown-not-measured` either quantity absent, non-numeric, NaN,
                            infinite, or nonsensical (negative elapsed,
                            non-positive window)

  Malformed input reads as unmeasured rather than as either verdict --
  the safe direction, because the alternative is a definite claim derived
  from bad data. Landing exactly ON the boundary is `:outside`: at that
  instant the window has closed."
  [evidence]
  (let [{:keys [window-elapsed-hours window-hours]} evidence]
    (if (and (finite-number? window-elapsed-hours)
             (finite-number? window-hours)
             (>= window-elapsed-hours 0)
             (> window-hours 0))
      (if (< window-elapsed-hours window-hours) :inside :outside)
      :unknown-not-measured)))

(defn notice-verdict
  "Did the notice period given for a transition into `to-stage` on `track`
  meet the statutory minimum?

    `:not-applicable`       this stage carries no minimum
    `:satisfied`            days given >= the minimum
    `:too-short`            days given < the minimum
    `:unknown-not-measured` a minimum applies but `:notice-days` is absent
                            or malformed

  Unlike `window-verdict` this compares against a number the statute
  fixes, so `:too-short` is a definite finding and
  `transition-violations` treats it as one."
  [track to-stage evidence]
  (if-let [minimum (get-in (track-spec track) [:minimum-notice-days to-stage])]
    (let [given (:notice-days evidence)]
      (cond
        (not (finite-number? given)) :unknown-not-measured
        (< given 0) :unknown-not-measured
        (>= given minimum) :satisfied
        :else :too-short))
    :not-applicable))

(defn time-critical-stage?
  "Is a transition INTO `stage` one whose lateness is a real failure mode
  on `track`?"
  [track stage]
  (contains? (:time-critical-stages (track-spec track)) stage))

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
                     claims to supply.

  On a track carrying statutory notice minima there is a third rule,
  `:notice-period-too-short`, raised only when `:notice-days` IS supplied
  and falls below the minimum. An ABSENT `:notice-days` is not a
  violation -- it is recorded as `:unknown-not-measured` by
  `apply-transition` instead. Same for lateness: this function never
  blocks a transition for being late or unmeasured, because a late freeze
  still has to be recorded truthfully rather than refused."
  ([current-stage to-stage evidence]
   (transition-violations default-track current-stage to-stage evidence))
  ([track current-stage to-stage evidence]
   (let [{:keys [consequential-stages] :as spec} (track-spec track)]
     (if-not spec
       [{:rule :unknown-regulatory-track
         :detail (str (pr-str track) " is not a registered track (registered: "
                      (pr-str (set (keys tracks))) ") -- refusing rather than"
                      " validating against a different chain")}]
       (into []
             (concat
              (when (or (nil? to-stage) (not (valid-transition? track current-stage to-stage)))
                [{:rule :regulatory-transition-invalid
                  :detail (str current-stage " -> " (pr-str to-stage)
                               " is not an allowed single-step transition (allowed: "
                               (pr-str (next-stages track current-stage)) ")")}])
              (when (and (contains? consequential-stages to-stage)
                         (not (evidence-complete? evidence)))
                [{:rule :regulatory-evidence-missing
                  :detail (str "transition into " to-stage " requires all of " (pr-str evidence-keys)
                               " as independently human-supplied, non-blank strings"
                               " -- never defaulted or auto-generated")}])
              (when (= :too-short (notice-verdict track to-stage evidence))
                [{:rule :notice-period-too-short
                  :detail (str "transition into " to-stage " gave "
                               (pr-str (:notice-days evidence)) " days of notice;"
                               " the statutory minimum is "
                               (get-in spec [:minimum-notice-days to-stage]))}])))))))

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
  independent decision authority.

  On a track with `:time-critical-stages` or `:minimum-notice-days`, the
  history entry additionally carries `:window` and/or `:notice` -- the
  verdicts from `window-verdict`/`notice-verdict` at the moment the
  transition was applied. They are written whenever the stage is subject
  to them, INCLUDING when the answer is `:unknown-not-measured`, so a
  record can never later be read as 'we froze it in time' on the strength
  of nobody having checked. This function still does not block on either."
  ([existing transition]
   ;; `default-track`, NOT `(:regulatory-track transition)`. That key is
   ;; free-form caller data (`:india-cdsco`, `:gcc-gso`, ...) and always
   ;; has been; reading it as a chain selector would silently hand every
   ;; existing caller an `:unknown-regulatory-track` violation. Track
   ;; selection is explicit, via the 3-arity, or it does not happen.
   (apply-transition default-track existing transition))
  ([track existing {:keys [submission-id subject-id regulatory-track to-stage] :as transition}]
   (let [start-stage (or (first (:ordered-stages (track-spec track))) :draft)
         current-stage (:status existing start-stage)
         evidence (select-keys transition evidence-keys)
         violations (transition-violations track current-stage to-stage transition)]
     (if (seq violations)
       {:ok? false :violations violations :record existing}
       {:ok? true
        :record (-> (or existing
                        {:submission-id submission-id
                         :subject-id subject-id
                         :regulatory-track regulatory-track
                         :history []})
                    (update :history (fnil conj [])
                            (cond-> {:from current-stage :to to-stage :evidence evidence}
                              (time-critical-stage? track to-stage)
                              (assoc :window (window-verdict transition))
                              (not= :not-applicable (notice-verdict track to-stage transition))
                              (assoc :notice (notice-verdict track to-stage transition))))
                    (assoc :status to-stage)
                    (merge evidence))}))))
