(ns hive-kdenlive.manifest-test
  "The mount manifest is a claim about the addon it names, read by hosts that
   never load this namespace. `addon_test.clj` calls `addon-ctor` directly, so
   until now nothing checked that the MANIFEST reaches it: a typo in
   :addon/init-ns or :addon/init-fn, a capability that drifted, a tool that
   vanished, would all fail at host load time rather than in the suite. That is
   card 20260913211854-2fcb3049.

   Modelled on hive-gimp's manifest_test.

   Written first against two defects, both since fixed and now asserted fixed:

   - the manifest declared :addon/maturity :alpha, which MountSpec's enum
     ([:beta :dormant :experimental :stable]) does not contain, so
     `boundary/parse-spec` answered :mount/spec-invalid and schema-validated
     discovery found ZERO specs for hive.kdenlive while the reflective binding
     underneath was sound. Now :experimental.
   - the description named neither `ping` nor `inspect_project`. It now ends
     with a Tools: list, and `description-claims` maps every published tool.

   The lifecycle assertions need hive-addon 1.0.7 or later
   (hive-addon.lifecycle.policy); the repo pins 1.0.8."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.lifecycle.policy :as policy]
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

(deftest the-manifest-is-a-valid-mount-spec-that-discovery-finds-test
  ;; This test pinned a defect until 2026-09-13: the manifest declared
  ;; :addon/maturity :alpha, which MountSpec's enum does not contain, so
  ;; schema-validated discovery found ZERO specs for hive.kdenlive. Fixed to
  ;; :experimental; this now asserts the fix and would catch its return.
  (let [parsed (boundary/parse-spec (manifest-text))]
    (is (r/ok? parsed) (pr-str (:explanation parsed)))
    (is (= :experimental (:addon/maturity (manifest))))
    (testing "schema-validated discovery mounts it, once, with no error for this file"
      (let [{:keys [specs errors]} (boundary/discover-specs)]
        (is (= 1 (count (filter #(= "hive.kdenlive" (:addon/id %)) specs))))
        (is (empty? (filter #(str/includes? (str (:url %)) "hive-kdenlive.edn") errors)))))))

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
   tool cannot be added or dropped without this test saying so.

   Every name is prefixed kdenlive_ since 2026-09-20. A host mounts many
   addons into one tool namespace, so a bare `ping` or `render` claims a name
   this addon does not own."
  #{"kdenlive_render" "kdenlive_call" "kdenlive_routes"
    "kdenlive_ping" "kdenlive_inspect_project"})

(deftest every-published-tool-is-namespaced-to-this-addon-test
  (testing "a bare verb would collide with any other addon in the same host"
    (doseq [t published-tools]
      (is (str/starts-with? t "kdenlive_") (str t " is not prefixed")))))

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
  "tool name -> the phrase in :addon/description that advertises it.

   Written down rather than derived, so drift is detectable: rename a tool or
   reword the description and one of the assertions below fails. Every
   published tool is advertised since 2026-09-13, when the description gained
   its Tools: list."
  {"kdenlive_render"          "headless melt rendering"
   "kdenlive_call"            "HTTP transport to the Kdenlive scripting fork"
   "kdenlive_routes"          "route catalog"
   "kdenlive_ping"            "kdenlive_ping"
   "kdenlive_inspect_project" "kdenlive_inspect_project"})

(deftest the-description-advertises-only-tools-that-exist-test
  (let [description (:addon/description (manifest))]
    (is (not (str/blank? description)))
    (testing "every tool the claim table maps is a tool that is published"
      (is (= published-tools (set (keys description-claims)))))
    (testing "every advertised phrase is actually in the description"
      (doseq [[tool phrase] description-claims :when phrase]
        (is (str/includes? description phrase)
            (str "the description no longer advertises " tool))))))

(deftest the-lifecycle-resolves-to-lazy-with-a-fifteen-minute-idle-test
  (let [m (manifest)]
    (is (= {:policy :lazy :idle-ms 900000} (policy/resolve-lifecycle m nil nil)))
    (testing "no surface is declared, so a host learns it from the first mount"
      (is (nil? (:addon/surface m))))
    (testing "a host override still wins over the manifest"
      (is (= :pinned (:policy (policy/resolve-lifecycle m nil {:policy :pinned})))))))
