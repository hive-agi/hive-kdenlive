(ns hive-kdenlive.manifest-test
  "The mount manifest is a claim about the addon it names, read by hosts that
   never load this namespace. `addon_test.clj` calls `addon-ctor` directly, so
   until now nothing checked that the MANIFEST reaches it: a typo in
   :addon/init-ns or :addon/init-fn, a capability that drifted, a tool that
   vanished, would all fail at host load time rather than in the suite. That is
   card 20260913211854-2fcb3049.

   Modelled on hive-gimp's manifest_test, minus the lifecycle assertions --
   `hive-addon.lifecycle.policy` does not exist in hive-addon 1.0.4, the version
   this repo pins.

   ONE OF THESE TESTS PINS A DEFECT rather than an invariant. The manifest
   declares

       :addon/maturity :alpha

   and hive-addon 1.0.4's MountSpec schema spells that field

       [:addon/maturity {:optional true :default :experimental}
        [:enum :beta :dormant :experimental :stable]]

   so `boundary/parse-spec` answers :mount/spec-invalid and
   `boundary/discover-specs` finds ZERO specs and one error. The reflective
   binding underneath is sound -- the constructor resolves and builds a correct
   IAddon, which is what every other test here proves -- but any host that
   mounts through the schema-validated discovery path skips this addon
   entirely. The fix is one word in
   resources/META-INF/hive-addons/hive-kdenlive.edn (`:alpha` ->
   `:experimental`, or drop the key and take the default); it is not made here
   because this branch is confined to new files.

   `the-manifest-is-rejected-by-the-mountspec-schema-test` therefore asserts the
   rejection, with the exact explanation. The day the manifest is fixed that
   test fails and says so, which is the point: the defect cannot be forgotten
   and the fix cannot land silently."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.mount.boundary :as boundary]
            [hive-addon.protocol :as addon]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def manifest-resource "META-INF/hive-addons/hive-kdenlive.edn")

(defn- manifest-text [] (slurp (io/resource manifest-resource)))

(defn- manifest [] (edn/read-string (manifest-text)))

(defn- classpath-copies []
  (->> (.getResources (.getContextClassLoader (Thread/currentThread))
                      manifest-resource)
       enumeration-seq
       (mapv str)))

;; ------------------------------------------------ the manifest on the path

(deftest the-classpath-scan-finds-the-manifest-exactly-once-test
  (testing "one manifest, so a host cannot mount two rival hive.kdenlive addons"
    (is (= 1 (count (classpath-copies))) (pr-str (classpath-copies)))))

(deftest the-manifest-is-well-formed-and-declares-the-mount-keys-test
  (let [m (manifest)]
    (is (map? m))
    (is (= "hive.kdenlive" (:addon/id m)))
    (is (= :native (:addon/type m)))
    (is (= :addon (:addon/kind m)))
    (is (= #{:tools :health-reporting} (:addon/capabilities m)))
    (testing "the two keys that carry the binding are present and non-blank"
      (is (not (str/blank? (:addon/init-ns m))))
      (is (not (str/blank? (:addon/init-fn m)))))))

(deftest the-manifest-is-rejected-by-the-mountspec-schema-test
  ;; See the namespace docstring: this pins a DEFECT. Fixing :addon/maturity
  ;; makes this test fail, and the fix is to delete this test and assert
  ;; (r/ok? parsed) instead, the way hive-gimp's manifest_test does.
  (let [parsed (boundary/parse-spec (manifest-text))]
    (testing "hive-addon 1.0.4 does not accept :alpha as a maturity"
      (is (false? (r/ok? parsed))
          "MANIFEST FIXED? swap this test for (is (r/ok? parsed))")
      (is (= :mount/spec-invalid (:error parsed)))
      (is (= {:addon/maturity ["should be either :beta, :dormant, :experimental or :stable"]}
             (:explanation parsed)))
      (is (= :alpha (:addon/maturity (manifest)))))
    (testing "so schema-validated discovery skips this addon entirely"
      (let [{:keys [specs errors]} (boundary/discover-specs)]
        (is (empty? (filter #(= "hive.kdenlive" (:addon/id %)) specs)))
        (is (= 1 (count (filter #(str/includes? (str (:url %)) "hive-kdenlive.edn")
                                errors))))))))

;; ------------------------------------- the binding the manifest declares

(defn- resolve-ctor
  "Exactly what a host does with :addon/init-ns and :addon/init-fn: no direct
   reference to hive-kdenlive.addon anywhere in this namespace, so a typo in
   either key fails here."
  [m]
  (let [init-ns (symbol (:addon/init-ns m))]
    (require init-ns)
    (ns-resolve init-ns (symbol (:addon/init-fn m)))))

(deftest the-constructor-the-manifest-names-resolves-test
  (let [m (manifest)]
    (is (some? (resolve-ctor m))
        (str (:addon/init-ns m) "/" (:addon/init-fn m) " does not resolve"))
    (is (fn? @(resolve-ctor m)))))

(deftest the-constructor-builds-the-addon-the-manifest-describes-test
  (let [m (manifest)
        a ((resolve-ctor m) (:addon/config m))]
    (testing "identity, type and capabilities agree with the manifest"
      (is (satisfies? addon/IAddon a))
      (is (= (:addon/id m) (addon/addon-id a)))
      (is (= (:addon/type m) (addon/addon-type a)))
      (is (= (:addon/capabilities m) (addon/capabilities a))))
    (testing "the declared capabilities are actually delivered"
      (try
        (is (:success? (addon/initialize! a {})))
        (is (seq (addon/tools a)) ":tools is declared, so tools must be published")
        (is (= :ok (:status (addon/health a)))
            ":health-reporting is declared, so health must answer")
        (finally (addon/shutdown! a))))))

(def published-tools
  "The surface a host sees after mounting THIS manifest. Pinned by name so a
   tool cannot be added or dropped without this test saying so."
  #{"render" "kdenlive_call" "routes" "ping" "inspect_project"})

(deftest the-published-tools-are-the-declared-surface-test
  (let [m     (manifest)
        a     ((resolve-ctor m) (:addon/config m))
        tools (addon/tools a)]
    (try
      (is (= published-tools (into #{} (map :name) tools)))
      (is (= (count published-tools) (count tools)) "no duplicate tool names")
      (doseq [{:keys [name description inputSchema handler]} tools]
        (testing name
          (is (not (str/blank? name)))
          (is (not (str/blank? description)))
          (is (= "object" (:type inputSchema)))
          (is (fn? handler))))
      (finally (addon/shutdown! a)))))

;; ------------------------------- what the description advertises, and does not

(def description-claims
  "tool name -> the phrase in :addon/description that advertises it, or nil when
   the description does not mention that surface at all.

   Unlike hive-gimp's manifest, this description is prose rather than a
   parenthesised tool list, so the correspondence has to be written down. Doing
   so is what makes drift detectable: rename a tool or reword the description
   and one of the two assertions below fails."
  {"render"          "headless melt rendering"
   "kdenlive_call"   "HTTP transport to the Kdenlive scripting fork"
   "routes"          "route catalog"
   "ping"            nil
   "inspect_project" nil})

(deftest the-description-advertises-only-tools-that-exist-test
  (let [description (:addon/description (manifest))]
    (is (not (str/blank? description)))
    (testing "every tool the claim table maps is a tool that is published"
      (is (= published-tools (set (keys description-claims)))))
    (testing "every advertised phrase is actually in the description"
      (doseq [[tool phrase] description-claims :when phrase]
        (is (str/includes? description phrase)
            (str "the description no longer advertises " tool))))))

(deftest the-description-under-advertises-two-published-tools-test
  ;; A second pinned gap, same shape as the maturity one. `ping` and
  ;; `inspect_project` are published but named nowhere in :addon/description, so
  ;; a host listing addons by description under-reports this one. Proposed edit,
  ;; not made here because this branch is confined to new files: append
  ;; " Tools: render, kdenlive_call, routes, ping, inspect_project." to
  ;; :addon/description, then move those two entries of `description-claims`
  ;; off nil and delete this test.
  (let [unadvertised (set (keep (fn [[tool phrase]] (when (nil? phrase) tool))
                                description-claims))]
    (is (= #{"ping" "inspect_project"} unadvertised)
        "DESCRIPTION FIXED? move the tool off nil in description-claims")))
