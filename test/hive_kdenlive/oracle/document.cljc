(ns hive-kdenlive.oracle.document
  "THE canonical MLT document, and the comparator three hosts judge themselves
   with. One definition, one expected artifact, three readers.

   Before this namespace existed the JVM suite, dev/portability.cljw and
   native/src/hive_kdenlive/native_probe.cljrs each carried their OWN copy of
   what emission should produce -- three transcriptions that could drift apart
   silently, which is the whole complaint of card 20260913211854-62b918fb. Here
   the document is built once, from the public builders of
   `hive-kdenlive.mlt.model`, and every host emits THAT and compares against the
   same `dev/oracle/expected.mlt` on disk.

   The document is deliberately awkward where emission is allowed to differ:

     * a fractional frame rate (29.97), so the [30000 1001] rounding is compared
       across three float implementations rather than three copies of the answer
     * `\"`, `&`, `<` and `>` inside an attribute value (a producer id), which is
       the half of escaping a round-trip property cannot see
     * a newline and a tab inside a property's TEXT, which emission escapes as
       the numeric entities &#10; and &#9; in attributes but leaves literal in
       text -- a difference no single host can check alone
     * empty, self-closing and text-bearing elements, plus a nested tractor

   Properties are given as VECTORS OF PAIRS, never maps: `model/properties`
   emits in seq order, so a map would make the fixture depend on each host's
   map iteration order rather than on its emitter. The fixture pins emission.

   Portable on purpose: clojure.core and clojure.string only, no reader
   conditionals, so cljw and cljrs load this file unchanged.

   Paths are relative to the repository root, which is the working directory all
   three runners are launched from."
  (:require [clojure.string :as str]
            [hive-kdenlive.mlt.model :as model]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def expected-path "dev/oracle/expected.mlt")

(def edn-path "dev/oracle/document.edn")

(def spicy-id
  "A producer id carrying every character attribute escaping has to handle."
  "red \"one\" & <two>")

(defn build
  "The canonical document. A function as well as a def so a host can rebuild it
   after reloading the model namespace."
  []
  (model/document
   (model/profile :width 320 :height 240 :fps 29.97
                  :sar [1 1] :dar [4 3] :colorspace 601)
   (model/producer "black" :id "black_track"
                   :properties [["mlt_service" "color"] ["length" "75"]])
   (model/producer "#ff0000" :id spicy-id
                   :properties [["mlt_service" "color"]])
   (model/producer "/tmp/clip a&b.mkv" :id "clip" :in "0" :out "39"
                   :properties [["mlt_service" "avformat"]
                                ["meta.attr.title" "line one\nline two\tafter a tab"]
                                [:audio_index "1"]])
   (model/playlist "background" [(model/entry "black_track" :in "0" :out "74")])
   (model/playlist "v1" [(model/entry spicy-id :in "0" :out "24")
                         (model/blank 10)
                         (model/entry "clip" :in "0" :out "39")])
   (model/playlist "empty" [])
   (model/tractor "main"
                  [(model/track "background")
                   (model/track "v1" :hide :audio)]
                  :transitions [(model/transition "frei0r.cairoblend" "0" "1"
                                                  [["disable" "0"]]
                                                  :in "0" :out "74")]
                  :filters [(model/filter "volume" [["level" "0.5"]] :id "f0")])))

(def document (build))

(defn emit
  "The bytes this host produces for the canonical document."
  []
  (model/emit (build)))

;; ------------------------------------------------------- the one comparator

(defn- line-at [v i] (if (< i (count v)) (nth v i) nil))

(defn diff-report
  "nil when EXPECTED and ACTUAL are byte-identical. Otherwise a unified diff of
   the first differing line, with two lines of context. Shared by all three
   hosts so a divergence reads the same wherever it is found."
  [expected actual]
  (when-not (= expected actual)
    (let [e (vec (str/split-lines expected))
          a (vec (str/split-lines actual))
          n (max (count e) (count a))
          i (first (filter (fn [i] (not= (line-at e i) (line-at a i)))
                           (range n)))]
      (if (nil? i)
        ;; Same lines, different bytes: only trailing whitespace can do that.
        (str "--- " expected-path " (" (count expected) " bytes)\n"
             "+++ emitted here (" (count actual) " bytes)\n"
             "@@ identical lines, different trailing bytes @@\n"
             "-" (pr-str (subs expected (max 0 (- (count expected) 20)))) "\n"
             "+" (pr-str (subs actual (max 0 (- (count actual) 20)))))
        (let [from (max 0 (- i 2))
              ctx  (fn [v] (str/join "\n" (map (fn [j] (str "  " (line-at v j)))
                                               (range from i))))]
          (str "--- " expected-path "\n"
               "+++ emitted here\n"
               "@@ first difference at line " (inc i) " @@\n"
               (let [c (ctx e)] (if (= "" c) "" (str c "\n")))
               "- " (pr-str (line-at e i)) "\n"
               "+ " (pr-str (line-at a i))))))))

(defn check
  "Compare this host's emission against EXPECTED. Returns
   {:ok? bool :report string-or-nil :bytes n}."
  [expected]
  (let [actual (emit)
        report (diff-report expected actual)]
    {:ok?    (nil? report)
     :report report
     :bytes  (count actual)}))

(def default-iterations
  "Why a byte oracle repeats itself instead of emitting once.

   A host may execute the SAME function differently once it has been called
   often enough: clojurust promotes a hot function to another tier, and
   tier-dependent divergence has been observed there -- true?/false?/identical?
   answering wrongly from the 51st call on (memory 20260913213113-2bfe93b3). A
   gate that builds and emits the document a single time therefore only ever
   tests the interpreter, and would report agreement for an emitter that breaks
   the moment real work warms it up.

   200 is past every threshold named so far, and the whole document is 1.7 kB,
   so the repeat costs milliseconds."
  200)

(defn check-repeatedly
  "`check` run N times, rebuilding and re-emitting the document each pass, so
   the comparison spans whatever execution tiers the host promotes it through.
   Answers like `check`, plus :iterations (how many passes ran) and
   :diverged-at (the 1-based pass that first disagreed, nil when all agreed).
   Stops at the first divergence -- the diff is the same one every later pass
   would print."
  [expected n]
  (loop [i 1]
    (let [result (check expected)]
      (cond
        (not (:ok? result)) (assoc result :iterations i :diverged-at i)
        (>= i n)            (assoc result :iterations i :diverged-at nil)
        :else               (recur (inc i))))))
