(ns cloud-itonami.regulatory-tracker.core-test
  "Mirrors `hygaccess.regulatory_test`/`hygaccess.governor_contract_test`'s
  own regulatory-submission-status scenario coverage (valid/invalid
  transitions, missing-evidence holds, full happy-path lifecycle), but
  written against this generic library's own free-form `:subject-id`/
  `:regulatory-track` API. Also proves genuine domain-agnosticism with a
  SECOND, unrelated domain scenario (a drone-operations airspace waiver,
  nothing to do with hygiene products) -- the concrete evidence that
  extraction actually generalized the reference implementation, not
  merely renamed it."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud-itonami.regulatory-tracker.core :as reg]))

;; ----------------------------- stage-chain structural invariants -----------------------------

(deftest ordered-stages-and-exit-stages-match-reference-chain
  (is (= [:draft :counsel-review :submitted :agency-review :approved] reg/ordered-stages)
      "the ordered chain ends in the SUCCESS outcome :approved -- kotoba.crm.pipeline's own convention for its terminal 'reached the end' stage")
  (is (= #{:rejected :withdrawn} reg/exit-stages)
      "only the two ABANDONMENT outcomes are exit-stages -- see ns docstring for why :approved is not one of them")
  (is (= #{:submitted :approved :rejected} reg/consequential-stages))
  (is (= [:filed-by :filing-date :agency-reference] reg/evidence-keys)))

(deftest valid-transition-allows-immediate-next-only
  (testing "immediate next stage is valid, including the terminal :agency-review -> :approved step"
    (is (reg/valid-transition? :draft :counsel-review))
    (is (reg/valid-transition? :counsel-review :submitted))
    (is (reg/valid-transition? :submitted :agency-review))
    (is (reg/valid-transition? :agency-review :approved)))
  (testing "skipping ahead is invalid -- no skipping states"
    (is (not (reg/valid-transition? :draft :submitted)))
    (is (not (reg/valid-transition? :draft :agency-review)))
    (is (not (reg/valid-transition? :counsel-review :agency-review)))
    (is (not (reg/valid-transition? :draft :approved)))
    (is (not (reg/valid-transition? :counsel-review :approved)))
    (is (not (reg/valid-transition? :submitted :approved)))))

(deftest exit-stage-reachable-from-any-non-terminal-stage
  (testing "the one documented, deliberate generalization from hygaccess.regulatory's stricter reference: this library follows kotoba.crm.pipeline's own semantics (rejected/withdrawn reachable from any non-terminal stage), not hygaccess's hand-written table (exit only from :agency-review). :approved is unaffected -- it still requires walking the full chain (see valid-transition-allows-immediate-next-only)."
    (is (reg/valid-transition? :draft :withdrawn))
    (is (reg/valid-transition? :draft :rejected))
    (is (reg/valid-transition? :counsel-review :rejected))
    (is (reg/valid-transition? :submitted :withdrawn))
    (is (reg/valid-transition? :agency-review :rejected))
    (is (reg/valid-transition? :agency-review :withdrawn))))

(deftest no-transition-from-an-already-terminal-stage
  (is (not (reg/valid-transition? :approved :counsel-review)))
  (is (not (reg/valid-transition? :rejected :draft)))
  (is (not (reg/valid-transition? :withdrawn :submitted))))

(deftest terminal-stage-predicate
  (is (reg/terminal-stage? :approved))
  (is (reg/terminal-stage? :rejected))
  (is (reg/terminal-stage? :withdrawn))
  (is (not (reg/terminal-stage? :draft)))
  (is (not (reg/terminal-stage? :agency-review))
      "agency-review is NOT terminal -- it is the stage that fans out to :approved/:rejected/:withdrawn"))

;; ----------------------------- evidence-completeness -----------------------------

(deftest evidence-complete-requires-all-three-non-blank-fields
  (is (reg/evidence-complete? {:filed-by "counsel" :filing-date "2026-07-18"
                                :agency-reference "REF-0001"}))
  (is (not (reg/evidence-complete? {:filed-by "counsel" :filing-date "2026-07-18"})))
  (is (not (reg/evidence-complete? {:filed-by "" :filing-date "2026-07-18"
                                     :agency-reference "REF-0001"}))
      "blank string treated as missing")
  (is (not (reg/evidence-complete? {:filed-by "counsel" :filing-date "   "
                                     :agency-reference "REF-0001"}))
      "whitespace-only string treated as missing")
  (is (not (reg/evidence-complete? {}))))

;; ----------------------------- transition-violations -----------------------------

(deftest transition-invalid-violation-on-skip
  (let [violations (reg/transition-violations :draft :submitted {})]
    (is (some #{:regulatory-transition-invalid} (map :rule violations)))))

(deftest evidence-missing-violation-isolated-from-transition-check
  (testing "a VALID transition into a consequential stage without evidence -> :regulatory-evidence-missing ONLY, never :regulatory-transition-invalid"
    (let [violations (reg/transition-violations :counsel-review :submitted {})]
      (is (some #{:regulatory-evidence-missing} (map :rule violations)))
      (is (not (some #{:regulatory-transition-invalid} (map :rule violations)))))))

(deftest clean-transition-has-no-violations
  (is (= [] (reg/transition-violations :draft :counsel-review {})))
  (is (= [] (reg/transition-violations :counsel-review :submitted
                                        {:filed-by "counsel" :filing-date "2026-07-18"
                                         :agency-reference "REF-0001"}))))

;; ----------------------------- apply-transition -----------------------------

(deftest apply-transition-invalid-does-not-mutate
  (let [existing nil
        {:keys [ok? violations record]} (reg/apply-transition
                                          existing
                                          {:submission-id "sub-1" :subject-id "subject-1"
                                           :regulatory-track :example-track
                                           :to-stage :submitted})]
    (is (false? ok?))
    (is (some #{:regulatory-transition-invalid} (map :rule violations)))
    (is (nil? record) "brand-new submission stays unrepresented -- no record fabricated on a held transition")))

(deftest apply-transition-missing-evidence-preserves-prior-valid-state
  (testing "mirrors hygaccess.governor_contract_test's regulatory-evidence-missing-is-held: the earlier valid transition landed, the evidence-less one never does"
    (let [{:keys [record]} (reg/apply-transition nil {:submission-id "sub-2" :subject-id "subject-2"
                                                        :regulatory-track :example-track
                                                        :to-stage :counsel-review})
          held (reg/apply-transition record {:to-stage :submitted})]
      (is (= :counsel-review (:status record)))
      (is (false? (:ok? held)))
      (is (some #{:regulatory-evidence-missing} (map :rule (:violations held))))
      (is (= :counsel-review (:status (:record held)))
          "the evidence-less transition never lands -- record unchanged at :counsel-review"))))

(deftest apply-transition-full-happy-path-lifecycle
  (testing "draft -> counsel-review -> submitted -> agency-review -> approved, mirroring hygaccess's own full happy-path coverage"
    (let [r1 (reg/apply-transition nil {:submission-id "sub-3" :subject-id "subject-3"
                                         :regulatory-track :india-cdsco
                                         :to-stage :counsel-review})
          _ (is (true? (:ok? r1)))
          r2 (reg/apply-transition (:record r1) {:to-stage :submitted
                                                   :filed-by "local regulatory counsel"
                                                   :filing-date "2026-07-18"
                                                   :agency-reference "CDSCO-REF-0099"})
          _ (is (true? (:ok? r2)))
          r3 (reg/apply-transition (:record r2) {:to-stage :agency-review})
          _ (is (true? (:ok? r3)))
          r4 (reg/apply-transition (:record r3) {:to-stage :approved
                                                   :filed-by "local regulatory counsel"
                                                   :filing-date "2026-07-20"
                                                   :agency-reference "CDSCO-REF-0099"})
          final (:record r4)]
      (is (true? (:ok? r4)))
      (is (= :approved (:status final)))
      (is (= "subject-3" (:subject-id final)))
      (is (= :india-cdsco (:regulatory-track final)))
      (is (= "local regulatory counsel" (:filed-by final)))
      (is (= "CDSCO-REF-0099" (:agency-reference final)))
      (is (= 4 (count (:history final))))
      (is (= [:draft :counsel-review :submitted :agency-review]
             (mapv :from (:history final))))
      (is (= [:counsel-review :submitted :agency-review :approved]
             (mapv :to (:history final)))))))

;; ----------------------------- second, unrelated domain: genuine domain-agnosticism proof -----------------------------
;;
;; A drone-logistics airspace-operations waiver has nothing to do with
;; hygiene/disinfectant products, market+product-type pairs, or any
;; hygaccess-specific concept -- proving this library's engine only
;; ever operates on the free-form :subject-id/:regulatory-track pair
;; and the closed stage-chain, never anything hygiene-shaped.

(deftest second-domain-drone-airspace-waiver-full-lifecycle
  (testing "genuine domain-agnosticism: a completely different subject/regulatory-track pair (drone BVLOS airspace waiver, not hygiene) walks the exact same engine to :approved"
    (let [subject {:fleet-id "acme-drone-logistics-fleet-07" :operation-type :bvlos-delivery}
          r1 (reg/apply-transition nil {:submission-id "waiver-1" :subject-id subject
                                         :regulatory-track :faa-part107-waiver
                                         :to-stage :counsel-review})
          r2 (reg/apply-transition (:record r1) {:to-stage :submitted
                                                   :filed-by "airspace compliance counsel"
                                                   :filing-date "2026-07-10"
                                                   :agency-reference "FAA-WAIVER-2026-4471"})
          r3 (reg/apply-transition (:record r2) {:to-stage :agency-review})
          r4 (reg/apply-transition (:record r3) {:to-stage :approved
                                                   :filed-by "airspace compliance counsel"
                                                   :filing-date "2026-07-16"
                                                   :agency-reference "FAA-WAIVER-2026-4471"})
          final (:record r4)]
      (is (every? :ok? [r1 r2 r3 r4]))
      (is (= :approved (:status final)))
      (is (= subject (:subject-id final)))
      (is (= :faa-part107-waiver (:regulatory-track final)))
      (is (= 4 (count (:history final)))))))

(deftest second-domain-invalid-skip-and-missing-evidence-still-held
  (testing "the same HARD gates apply regardless of domain -- this is engine behavior, not per-domain logic"
    (let [skip (reg/apply-transition nil {:submission-id "waiver-2" :subject-id {:fleet-id "acme-drone-logistics-fleet-08"}
                                           :regulatory-track :faa-part107-waiver
                                           :to-stage :submitted})
          _ (is (false? (:ok? skip)))
          started (reg/apply-transition nil {:submission-id "waiver-2" :subject-id {:fleet-id "acme-drone-logistics-fleet-08"}
                                              :regulatory-track :faa-part107-waiver
                                              :to-stage :counsel-review})
          no-evidence (reg/apply-transition (:record started) {:to-stage :submitted})]
      (is (false? (:ok? no-evidence)))
      (is (some #{:regulatory-evidence-missing} (map :rule (:violations no-evidence))))
      (is (= :counsel-review (:status (:record no-evidence)))))))

;; ============================================================================
;; named tracks, and the :jpn-account-freeze chain
;; ============================================================================

(def freeze :jpn-account-freeze)

(defn- freeze-evidence [ref]
  {:filed-by "financial crime desk" :filing-date "2026-07-29" :agency-reference ref})

(deftest default-track-is-unchanged-by-the-track-registry
  (testing "the registry did not quietly redefine the chain this library shipped with"
    (let [spec (reg/track-spec reg/default-track)]
      (is (= :regulatory-submission reg/default-track))
      (is (= reg/ordered-stages (:ordered-stages spec)))
      (is (= reg/exit-stages (:exit-stages spec)))
      (is (= reg/consequential-stages (:consequential-stages spec)))
      (is (nil? (:time-critical-stages spec))
          "the submission chain has no lateness semantics and must not acquire any")
      (is (nil? (:minimum-notice-days spec)))))
  (testing "single-track arities still answer for the default chain"
    (is (reg/valid-transition? :draft :counsel-review))
    (is (= (reg/next-stages :draft) (reg/next-stages reg/default-track :draft)))
    (is (= (reg/terminal-stage? :approved) (reg/terminal-stage? reg/default-track :approved)))))

(deftest freeze-track-matches-the-statutory-chain
  (let [spec (reg/track-spec freeze)]
    (is (= [:reported :suspension :extinguishment-notice
            :rights-extinguished :distribution-notice :distribution-paid]
           (:ordered-stages spec)))
    (is (= #{:suspension-lifted :no-distribution} (:exit-stages spec)))
    (is (= #{:suspension :rights-extinguished :distribution-paid} (:consequential-stages spec)))
    (is (= #{:suspension} (:time-critical-stages spec)))
    (is (= {:rights-extinguished 60 :distribution-paid 30} (:minimum-notice-days spec))
        "60/30 days are the statutory minimum NOTICE periods, retrieved from zenginkyo 2026-07-29")
    (is (reg/terminal-stage? freeze :distribution-paid))
    (is (not (reg/terminal-stage? freeze :suspension)))
    (testing "exits are reachable from any non-terminal stage"
      (is (reg/valid-transition? freeze :reported :suspension-lifted))
      (is (reg/valid-transition? freeze :rights-extinguished :no-distribution)))
    (testing "no skipping"
      (is (not (reg/valid-transition? freeze :reported :rights-extinguished)))
      (is (not (reg/valid-transition? freeze :suspension :distribution-paid))))
    (testing "the two chains do not leak into each other"
      (is (not (reg/valid-transition? freeze :draft :counsel-review)))
      (is (not (reg/valid-transition? :draft :suspension))))))

(deftest unknown-track-fails-closed-everywhere
  (testing "a typo'd track id must never validate against a different chain"
    (is (false? (reg/valid-transition? :jpn-acount-freeze :reported :suspension)))
    (is (= #{} (reg/next-stages :jpn-acount-freeze :reported)))
    (is (true? (reg/terminal-stage? :jpn-acount-freeze :reported))
        "with no chain to consult, nothing may proceed")
    (is (nil? (reg/track-spec :jpn-acount-freeze)))
    (let [v (reg/transition-violations :jpn-acount-freeze :reported :suspension (freeze-evidence "X"))]
      (is (= [:unknown-regulatory-track] (mapv :rule v))))
    (let [r (reg/apply-transition :jpn-acount-freeze nil {:to-stage :suspension})]
      (is (false? (:ok? r)))
      (is (nil? (:record r))))))

;; ---------------------------- lateness ----------------------------

(deftest window-verdict-reports-three-states
  (is (= :inside (reg/window-verdict {:window-elapsed-hours 4.317 :window-hours 23.317})))
  (is (= :outside (reg/window-verdict {:window-elapsed-hours 30 :window-hours 23.317})))
  (is (= :outside (reg/window-verdict {:window-elapsed-hours 23.317 :window-hours 23.317}))
      "exactly on the boundary the window has closed"))

(deftest inside-is-unreachable-without-both-measurements
  ;; The load-bearing invariant. `:unknown-not-measured` is not a softer
  ;; `:inside` -- it is what the record says forever when nobody measured,
  ;; and no combination of partial or malformed input may produce a
  ;; confident verdict.
  (doseq [ev [{}
              {:window-elapsed-hours 4.317}
              {:window-hours 23.317}
              {:window-elapsed-hours nil :window-hours 23.317}
              {:window-elapsed-hours "4.317" :window-hours 23.317}
              {:window-elapsed-hours 4.317 :window-hours "23.317"}
              {:window-elapsed-hours -1 :window-hours 23.317}
              {:window-elapsed-hours 4.317 :window-hours 0}
              {:window-elapsed-hours 4.317 :window-hours -5}
              {:window-elapsed-hours ##NaN :window-hours 23.317}
              {:window-elapsed-hours 4.317 :window-hours ##NaN}
              {:window-elapsed-hours ##Inf :window-hours 23.317}
              {:window-elapsed-hours 4.317 :window-hours ##Inf}]]
    (is (= :unknown-not-measured (reg/window-verdict ev))
        (str "must not produce a confident verdict from " (pr-str ev)))))

(deftest reported-is-the-implicit-start-state
  ;; Each track's FIRST ordered stage is where a record that does not yet
  ;; exist already is -- `:draft` on the submission chain, `:reported` here.
  ;; So a brand-new freeze transitions nil -> :suspension; there is nothing
  ;; to "enter :reported" from.
  (let [self (reg/apply-transition freeze nil {:to-stage :reported})]
    (is (false? (:ok? self)))
    (is (= [:regulatory-transition-invalid] (mapv :rule (:violations self)))))
  (let [first-real (reg/apply-transition freeze nil
                                         (merge {:submission-id "frz-0" :subject-id "acct-76"
                                                 :regulatory-track freeze :to-stage :suspension}
                                                (freeze-evidence "DIC-2026-0000")))]
    (is (:ok? first-real))
    (is (= :reported (:from (first (:history (:record first-real))))))))

(deftest lateness-is-recorded-never-enforced
  (testing "a freeze that landed outside its window still applies, and says so"
    (let [late (reg/apply-transition freeze nil
                                     (merge {:submission-id "frz-1" :subject-id "acct-77"
                                             :regulatory-track freeze :to-stage :suspension
                                             :window-elapsed-hours 26.967 :window-hours 23.317}
                                            (freeze-evidence "DIC-2026-0001")))]
      (is (:ok? late) "lateness is not a validation failure -- a late freeze must still be recordable")
      (is (= :suspension (:status (:record late))))
      (is (= :outside (:window (last (:history (:record late))))))))
  (testing "an unmeasured time-critical transition is permanently marked unmeasured"
    (let [unmeasured (reg/apply-transition freeze nil
                                           (merge {:submission-id "frz-2" :subject-id "acct-78"
                                                   :regulatory-track freeze :to-stage :suspension}
                                                  (freeze-evidence "DIC-2026-0002")))]
      (is (:ok? unmeasured))
      (is (= :unknown-not-measured (:window (last (:history (:record unmeasured)))))
          "no measurement must never read later as 'we froze it in time'")))
  (testing "stages that are not time-critical carry no window verdict at all"
    (let [r (reg/apply-transition freeze {:status :suspension :history []} {:to-stage :extinguishment-notice})]
      (is (:ok? r))
      (is (not (contains? (last (:history (:record r))) :window))))))

;; ---------------------------- notice periods ----------------------------

(deftest notice-verdict-compares-against-the-statutory-minimum
  (is (= :not-applicable (reg/notice-verdict freeze :suspension {:notice-days 5})))
  (is (= :not-applicable (reg/notice-verdict reg/default-track :approved {:notice-days 5})))
  (is (= :satisfied (reg/notice-verdict freeze :rights-extinguished {:notice-days 60})))
  (is (= :satisfied (reg/notice-verdict freeze :rights-extinguished {:notice-days 90})))
  (is (= :too-short (reg/notice-verdict freeze :rights-extinguished {:notice-days 59})))
  (is (= :satisfied (reg/notice-verdict freeze :distribution-paid {:notice-days 30})))
  (is (= :too-short (reg/notice-verdict freeze :distribution-paid {:notice-days 29})))
  (doseq [ev [{} {:notice-days nil} {:notice-days "60"} {:notice-days -1} {:notice-days ##NaN}]]
    (is (= :unknown-not-measured (reg/notice-verdict freeze :rights-extinguished ev))
        (str "must not read " (pr-str ev) " as a satisfied notice period"))))

(deftest too-short-notice-is-a-violation-but-absent-notice-is-not
  (let [at-notice {:status :extinguishment-notice :history []}]
    (testing "a number below the statutory floor is a definite finding"
      (let [r (reg/apply-transition freeze at-notice
                                    (merge {:to-stage :rights-extinguished :notice-days 45}
                                           (freeze-evidence "DIC-2026-0003")))]
        (is (false? (:ok? r)))
        (is (some #{:notice-period-too-short} (map :rule (:violations r))))
        (is (= :extinguishment-notice (:status (:record r))) "the record is unchanged")))
    (testing "an absent number does not block, but is recorded as unmeasured"
      (let [r (reg/apply-transition freeze at-notice
                                    (merge {:to-stage :rights-extinguished}
                                           (freeze-evidence "DIC-2026-0004")))]
        (is (:ok? r))
        (is (= :unknown-not-measured (:notice (last (:history (:record r))))))))
    (testing "a satisfied period is recorded as satisfied"
      (let [r (reg/apply-transition freeze at-notice
                                    (merge {:to-stage :rights-extinguished :notice-days 60}
                                           (freeze-evidence "DIC-2026-0005")))]
        (is (:ok? r))
        (is (= :satisfied (:notice (last (:history (:record r))))))))))

;; ---------------------------- full walk ----------------------------

(deftest freeze-happy-path-walks-the-statutory-chain
  (let [r2 (reg/apply-transition freeze nil
                                 (merge {:submission-id "frz-full" :subject-id {:account "1234567" :bank "0001"}
                                         :regulatory-track freeze :to-stage :suspension
                                         :window-elapsed-hours 4.317 :window-hours 23.317}
                                        (freeze-evidence "DIC-2026-1000")))
        r3 (reg/apply-transition freeze (:record r2) {:to-stage :extinguishment-notice})
        r4 (reg/apply-transition freeze (:record r3)
                                 (merge {:to-stage :rights-extinguished :notice-days 61}
                                        (freeze-evidence "DIC-2026-1001")))
        r5 (reg/apply-transition freeze (:record r4) {:to-stage :distribution-notice})
        r6 (reg/apply-transition freeze (:record r5)
                                 (merge {:to-stage :distribution-paid :notice-days 31}
                                        (freeze-evidence "DIC-2026-1002")))
        final (:record r6)]
    (is (every? :ok? [r2 r3 r4 r5 r6]))
    (is (= :distribution-paid (:status final)))
    (is (= 5 (count (:history final))))
    (is (= [:reported :suspension :extinguishment-notice :rights-extinguished :distribution-notice]
           (mapv :from (:history final))))
    (is (= :inside (:window (first (:history final)))))
    (is (= [:satisfied :satisfied]
           (keep :notice (:history final))))
    (is (reg/terminal-stage? freeze (:status final)))
    (is (= #{} (reg/next-stages freeze (:status final))))))

(deftest freeze-consequential-stages-demand-the-same-three-field-evidence
  (let [no-evidence (reg/apply-transition freeze nil {:submission-id "frz-3" :subject-id "acct-79"
                                                     :regulatory-track freeze :to-stage :suspension})]
    (is (false? (:ok? no-evidence)))
    (is (some #{:regulatory-evidence-missing} (map :rule (:violations no-evidence))))
    (is (nil? (:record no-evidence)) "no record is created by a refused transition"))
  (testing "and from an existing record the stored stage is left untouched"
    (let [at-notice {:status :extinguishment-notice :history []}
          no-evidence (reg/apply-transition freeze at-notice {:to-stage :rights-extinguished :notice-days 60})]
      (is (false? (:ok? no-evidence)))
      (is (= [:regulatory-evidence-missing] (mapv :rule (:violations no-evidence)))
          "the transition itself is legal and the notice period is satisfied -- only the evidence is missing")
      (is (= at-notice (:record no-evidence))))))
