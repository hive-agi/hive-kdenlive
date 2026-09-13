(ns hive-kdenlive.kdenlive.catalog-sync-test
  "Anti-drift ratchet: every catalog route must exist in the scripting fork's
   route table. The catalog is a CLAIM about another codebase; this test is
   what keeps the claim honest. Point KDENLIVE_FORK at a kdenlive-mcp
   checkout; the test skips when unset."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hive-kdenlive.kdenlive.routes :as routes]))

(def ^:private fork-root
  (System/getenv "KDENLIVE_FORK"))

(def ^:private method->cxx
  {:get "GET" :post "POST" :put "PUT" :delete "DELETE_"})

(defn- registered-routes
  "The set of [method path] the fork registers, read from routetable.cpp."
  [root]
  (let [src (slurp (io/file root "kdenlive-server/src/scripting/routetable.cpp"))]
    (into #{}
          (map (fn [[_ m p]] [m p]))
          (re-seq #"HttpMethod::(GET|POST|PUT|DELETE_),\s*QStringLiteral\(\"([^\"]+)" src))))

(deftest catalog-routes-exist-in-the-fork-test
  (if-not (and fork-root (.exists (io/file fork-root "kdenlive-server/src/scripting/routetable.cpp")))
    (is true "KDENLIVE_FORK unset — skipped")
    (let [registered (registered-routes fork-root)]
      (is (pos? (count registered)) "non-vacuity: the route table parsed")
      (doseq [{:keys [id method path]} routes/catalog]
        (is (contains? registered [(method->cxx method) path])
            (str id " " (method->cxx method) " " path " is not registered in the fork"))))))
