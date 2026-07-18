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
