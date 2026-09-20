(ns hive-kdenlive.kdenlive.json-test
  "A hand-written codec is only honest if the round trip is pinned, so this
   leans on generators as well as on the shapes hive-creator actually sends."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-kdenlive.kdenlive.json :as json]))

(defn- round [x] (:ok (json/read (json/write x))))

;; ---------------------------------------------------------------------------
;; The shapes that were broken

(deftest the-shapes-hive-creator-sends-survive-the-round-trip-test
  (testing ":media/import sends a VECTOR of paths"
    (let [params {:paths ["/tmp/cut.mp4" "/tmp/bed.mp3"]}]
      (is (= "{\"paths\":[\"/tmp/cut.mp4\",\"/tmp/bed.mp3\"]}" (json/write params)))
      (is (= {"paths" ["/tmp/cut.mp4" "/tmp/bed.mp3"]} (round params)))))
  (testing ":clip/append-effect sends a NESTED map"
    (let [params {:id 7 :clipId 7 :effectId "fade_from_black" :params {:duration 15}}]
      (is (= {"id" 7 "clipId" 7 "effectId" "fade_from_black" "params" {"duration" 15}}
             (round params)))))
  (testing "both used to be rendered as Clojure literals"
    (is (not (re-find #"\[\"/tmp/cut.mp4\" \"/tmp/bed.mp3\"\]"
                      (json/write {:paths ["/tmp/cut.mp4" "/tmp/bed.mp3"]})))
        "a space-separated array is what `str` produced and is not JSON")))

;; ---------------------------------------------------------------------------
;; Scalars and edges

(deftest scalars-round-trip-test
  (doseq [x [nil true false 0 -1 42 3.5 -0.25 "" "plain"]]
    (is (= x (round x)) (pr-str x))))

(deftest strings-with-everything-that-must-be-escaped-test
  (doseq [s ["quote \" inside"
             "backslash \\ inside"
             "newline \n tab \t return \r"
             "both \" and \\ together"
             "unicode é 中"
             "slash / stays"]]
    (is (= s (round s)) (pr-str s))))

(deftest keywords-are-written-as-their-names-test
  (is (= "{\"binId\":\"x1\",\"trackId\":3}" (json/write (array-map :binId "x1" :trackId 3))))
  (testing "the fork's camelCase survives, which is the whole point"
    (is (= {"fpsNum" 30 "fpsDen" 1} (round {:fpsNum 30 :fpsDen 1})))))

(deftest nesting-round-trips-test
  (let [x {:a [1 2 {:b [true nil "s"]}] :c {:d {:e []}}}]
    (is (= {"a" [1 2 {"b" [true nil "s"]}] "c" {"d" {"e" []}}} (round x)))))

;; ---------------------------------------------------------------------------
;; Refusals

(deftest malformed-input-is-an-error-value-not-a-throw-test
  (doseq [[why s] {"unclosed object" "{\"a\":1"
                   "unclosed string" "{\"a\":\"x}"
                   "unclosed array"  "[1,2"
                   "bare word"       "nope"
                   "trailing input"  "{} {}"
                   "missing colon"   "{\"a\" 1}"
                   "not a string"    nil}]
    (testing why
      (is (some? (:error (json/read s))) (pr-str s)))))

(deftest a-real-fork-answer-parses-test
  (testing "the shapes the route catalog's :result names"
    (is (= {"id" 12} (:ok (json/read "{\"id\": 12}"))))
    (is (= {"ids" ["3" "4"]} (:ok (json/read "{\"ids\": [\"3\", \"4\"]}"))))
    (is (= {"success" true} (:ok (json/read "{\"success\": true}"))))
    (is (= {"duration" 450} (:ok (json/read "{\"duration\": 450}"))))))

;; ---------------------------------------------------------------------------
;; Generative

(def ^:private gen-json-scalar
  (gen/one-of [(gen/return nil) gen/boolean gen/small-integer gen/string-alphanumeric]))

(def ^:private gen-json-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 4)
                  (gen/map gen/string-alphanumeric inner {:max-elements 4})]))
   gen-json-scalar))

(defspec write-then-read-is-identity-for-string-keyed-data 300
  (prop/for-all [v gen-json-value]
    (= v (:ok (json/read (json/write v))))))

(defspec any-string-survives-escaping 300
  (prop/for-all [s gen/string]
    (= s (:ok (json/read (json/write s))))))
