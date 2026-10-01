(ns hive-kdenlive.root-tool-contract-test
  "Every hive.kdenlive tool must be operable from its schema alone."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hive-addon.protocol :as addon]
            [hive-addon.tool-contract.test :refer [deftest-root-tools]]
            [hive-kdenlive.addon :as k]))

(defn- tools [] (addon/tools (k/addon-ctor {})))

(defn- id-str [id] (if (keyword? id) (subs (str id) 1) (str id)))

(defn- call [tool-name input]
  ((:handler (first (filter #(= tool-name (:name %)) (tools)))) input))

(deftest-root-tools every-kdenlive-tool-satisfies-the-root-contract (tools))

(deftest routes-prefix-narrows-the-catalog
  (let [all    (get-in (call "kdenlive_routes" {}) [:ok :routes])
        prefix (some #(when-let [[_ p] (re-find #"^([^/]+/)" (id-str (:id %)))] p) all)
        some'  (get-in (call "kdenlive_routes" {"prefix" prefix}) [:ok :routes])]
    (is (some? prefix))
    (is (< 0 (count some') (count all)))
    (is (every? #(str/starts-with? (id-str (:id %)) prefix) some'))))

(deftest ping-targets-narrow-the-probe
  (is (= #{:melt} (set (keys (:ok (call "kdenlive_ping" {"targets" ["melt"]})))))))
