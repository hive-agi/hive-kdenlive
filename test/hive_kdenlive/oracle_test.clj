(ns hive-kdenlive.oracle-test
  "The JVM's leg of the cross-host byte oracle.

   `dev/oracle/expected.mlt` is the artifact all three hosts are judged against:
   the JVM emits it, and `dev/oracle.cljw` and `dev/oracle.cljrs` read the same
   file off disk and compare their own emission to it byte-for-byte. Three
   readers, one claim -- which is what card 20260913211854-62b918fb asked for,
   and what three hand-transcribed copies of the expectations could never give.

   This namespace is the half that stops the fixture rotting. It asserts the
   file on disk EQUALS the live JVM emission, so a change to `mlt.xml/emit` or
   to a builder fails here first, in the fast suite, instead of surviving until
   somebody runs a native host.

   Regenerating is a deliberate act, never a side effect of running the tests:

     (hive-kdenlive.oracle-test/regenerate!)

   A test that rewrote its own fixture could not fail, so `regenerate!` is a
   separate function and the deftests only ever read.

   MUTATION. Round-trip properties cannot see an emitter change that preserves
   meaning -- dropping the <?xml?> declaration, expanding a self-closing tag,
   re-indenting. `emission-mutants-change-the-bytes-test` asserts each of those
   DOES change what this oracle compares, which is the argument for having a
   byte fixture at all rather than only the laws in
   `hive-kdenlive.mlt.roundtrip-property-test`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.mlt.model :as model]
            [hive-kdenlive.mlt.xml :as xml]
            [hive-kdenlive.oracle.document :as oracle]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn regenerate!
  "Write both oracle artifacts from the live JVM. Call it by hand after an
   intentional change to emission or to the canonical document, and commit the
   result -- the native hosts read these files."
  []
  (io/make-parents (io/file oracle/expected-path))
  (spit (io/file oracle/expected-path) (oracle/emit))
  (spit (io/file oracle/edn-path) (str (pr-str oracle/document) "\n"))
  {:expected oracle/expected-path :edn oracle/edn-path})

(defn- expected-file [] (io/file oracle/expected-path))

(deftest the-fixture-exists-test
  (testing "the artifact the native hosts read is committed"
    (is (.exists (expected-file))
        (str oracle/expected-path " is missing -- run (regenerate!) from a REPL"))
    (is (.exists (io/file oracle/edn-path)))))

(deftest the-fixture-equals-the-live-jvm-emission-test
  (testing "expected.mlt cannot rot: it is compared to a fresh emit every run"
    (let [on-disk (slurp (expected-file))
          {:keys [ok? report]} (oracle/check on-disk)]
      (is ok? (str "dev/oracle/expected.mlt disagrees with this JVM.\n" report
                   "\nIf the change to emission was intended, run"
                   " (hive-kdenlive.oracle-test/regenerate!) and commit.")))))

(deftest emission-is-stable-across-execution-tiers-test
  (testing "the document emits identically however hot the emitter gets"
    ;; The native hosts run this same repeat: a host that promotes a hot
    ;; function to another execution tier can diverge only after warm-up, and a
    ;; gate that emits once would never reach that tier. See
    ;; `oracle/default-iterations` for the observed instance.
    (let [{:keys [ok? iterations diverged-at report]}
          (oracle/check-repeatedly (slurp (expected-file)) oracle/default-iterations)]
      (is ok? (str "diverged on pass " diverged-at "\n" report))
      (is (= oracle/default-iterations iterations))
      (is (nil? diverged-at)))))

(deftest the-edn-fixture-is-the-same-document-test
  (testing "document.edn is the canonical document as data, not a stale copy"
    (is (= oracle/document (edn/read-string (slurp (io/file oracle/edn-path)))))))

(deftest the-fixture-is-a-parseable-mlt-document-test
  (let [{:keys [ok error]} (model/parse (slurp (expected-file)))]
    (is (nil? error))
    (is (= "mlt" (:tag ok)))
    (testing "and round-trips, so the fixture is inside the representable subset"
      (is (= ok (:ok (model/parse (model/emit ok))))))))

(deftest the-fixture-carries-the-cases-it-claims-test
  (testing "attribute escaping: the awkward id is escaped, not emitted raw"
    (let [out (slurp (expected-file))]
      (is (str/includes? out "id=\"red &quot;one&quot; &amp; &lt;two&gt;\""))
      (is (not (str/includes? out (str "id=\"" oracle/spicy-id "\""))))))
  (testing "text escaping and literal whitespace inside a property value"
    (let [out (slurp (expected-file))]
      (is (str/includes? out "/tmp/clip a&amp;b.mkv"))
      (is (str/includes? out "line one\nline two\tafter a tab"))))
  (testing "the fractional frame rate resolved to the NTSC pair"
    (is (str/includes? (slurp (expected-file))
                       "frame_rate_num=\"30000\" frame_rate_den=\"1001\"")))
  (testing "an empty element is self-closed"
    (is (str/includes? (slurp (expected-file)) "<playlist id=\"empty\"/>"))))

;; ----------------------------------------------------------------- mutation

(def emission-mutants
  "Emitter changes that PRESERVE meaning, so every round-trip law in
   `roundtrip-property-test` passes under them. Only a byte fixture notices.
   Each must change the bytes."
  {"the <?xml?> declaration dropped"
   {#'xml/emit (let [original xml/emit]
                 (fn [node] (str/replace-first (original node)
                                               "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                                               "")))}

   "two-space indentation instead of one"
   {#'xml/emit (let [original xml/emit]
                 (fn [node]
                   (str/join "\n" (map (fn [line]
                                         (let [n (count (take-while #(= \space %) line))]
                                           (str (apply str (repeat (* 2 n) " "))
                                                (subs line n))))
                                       (str/split-lines (original node))))))}

   "self-closing tags expanded to an empty element pair"
   {#'hive-kdenlive.mlt.xml/emit-node
    (let [original @#'xml/emit-node]
      (fn [node depth]
        (mapv (fn [line]
                (if (str/ends-with? line "/>")
                  (str (subs line 0 (- (count line) 2)) "></" (:tag node) ">")
                  line))
              (original node depth))))}})

(deftest emission-mutants-change-the-bytes-test
  (let [expected (slurp (expected-file))]
    (testing "unmutated, the oracle agrees"
      (is (:ok? (oracle/check expected))))
    (doseq [[label overrides] emission-mutants]
      (testing label
        (is (false? (:ok? (with-redefs-fn overrides
                            (fn [] (oracle/check expected)))))
            (str "MUTATION SURVIVED: '" label
                 "' left the oracle bytes unchanged -- the fixture does not"
                 " pin emission"))))))

(deftest the-comparator-reports-the-first-difference-test
  (testing "diff-report is nil on agreement and locates the line on divergence"
    (let [out (oracle/emit)]
      (is (nil? (oracle/diff-report out out)))
      (let [report (oracle/diff-report out (str/replace out "\"320\"" "\"640\""))]
        (is (str/includes? report "first difference at line 3"))
        (is (str/includes? report "width=\\\"320\\\""))
        (is (str/includes? report "width=\\\"640\\\"")))))
  (testing "a missing trailing newline is invisible to a line diff, not to this"
    ;; str/split-lines drops the trailing empty line, so a host that forgot the
    ;; final newline produces identical LINES and different BYTES. That is a
    ;; real cross-host difference, and the comparator has to name it.
    (let [out    (oracle/emit)
          report (oracle/diff-report out (subs out 0 (dec (count out))))]
      (is (some? report))
      (is (str/includes? report "different trailing bytes")))))
