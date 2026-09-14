(ns hive-kdenlive.mlt.roundtrip-property-test
  "Generative round-trip laws for the MLT document vocabulary.

   Documents are built ONLY through the public builders of
   `hive-kdenlive.mlt.model`, so every generated value is one the library can
   actually produce; a generator over raw node maps would invent documents no
   caller can construct and report failures nobody can hit.

   Laws, over that generated corpus:

     value    (= doc (:ok (parse (emit doc))))
     bytes    (= (emit doc) (emit (:ok (parse (emit doc)))))
     fixpoint one round-trip is idempotent, for EVERY document (see below)
     totality (parse s) answers with data for any string, and never throws

   REPRESENTABLE SUBSET. The value law holds on the subset the generators
   produce, which is narrower than the builders' argument types in two measured
   ways, both pinned as example tests at the bottom of this namespace:

     1. attribute values must be STRINGS. The builders pass :id/:in/:out/:length
        through unconverted, emission stringifies them, and parsing returns
        strings -- so (producer \"r\" :id 7) emits id=\"7\" and parses back \"7\".
        Byte-stable, not value-stable.
     2. text values must be NON-EMPTY. A <property> whose value is \"\" emits as
        <property name=\"k\"></property> and parses back with :content [],
        which re-emits as <property name=\"k\"/>. That one is NOT byte-stable
        either, and is the asymmetry these generators found.

   The `fixpoint` law is the one that survives both: whatever emit/parse lose,
   they lose it in the first round-trip and never again.

   WHAT THESE LAWS CANNOT SEE. `escaping-is-under-constrained-test` measures it:
   of the seven characters emission escapes, a round-trip only NOTICES a missing
   escape for `\"` in an attribute and `<` in text. Dropping the escape of `&`,
   `>`, newline, tab or carriage return still round-trips through this parser --
   while emitting XML that a conforming parser (the one inside MLT) rejects.
   That hole is why `hive-kdenlive.oracle-test` compares BYTES against a fixture
   shared with the cljw and cljrs hosts, and why mutants that change emission
   without changing meaning are asserted there rather than here.

   MUTATION. `mutants-die-test` re-runs the value law against deliberately
   broken emitters and asserts each one FAILS it, over `gen-escaping-document`
   -- a generator that carries the two load-bearing characters on EVERY draw, so
   a surviving mutant means a weak law rather than an unlucky seed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-kdenlive.mlt.model :as model]
            [hive-kdenlive.mlt.xml :as xml]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; ---------------------------------------------------------------- generators

(def ^:private gen-plain-char
  (gen/elements (vec "abcdeXYZ019 ._-/:#%")))

(def ^:private gen-escapable-char
  "The characters emission has to escape, or the round-trip is a lie."
  (gen/elements [\& \< \> \" \' \newline \tab \return]))

(def ^:private gen-char
  (gen/frequency [[5 gen-plain-char] [3 gen-escapable-char]]))

(def ^:private gen-text
  "A NON-EMPTY string: the empty text node is outside the representable subset."
  (gen/fmap str/join (gen/vector gen-char 1 12)))

(def ^:private gen-attr-value
  "An attribute value, possibly empty -- empty attributes DO round-trip."
  (gen/fmap str/join (gen/vector gen-char 0 10)))

(def ^:private gen-id
  "Identifiers carry an escapable character too: an id is an ATTRIBUTE value,
   and attributes were the half of escaping the first draft of these generators
   never exercised."
  (gen/let [n gen/nat, c gen-char] (str "id-" n c)))

(def ^:private gen-frames (gen/fmap str gen/nat))

(def ^:private gen-optional-frames (gen/one-of [(gen/return nil) gen-frames]))

(def ^:private gen-property-map
  (gen/map (gen/one-of [(gen/elements [:mlt_service :length :astream :force_aspect_ratio])
                        (gen/fmap #(str "k" %) gen/nat)])
           gen-text
           {:max-elements 3}))

(def gen-producer
  (gen/let [resource gen-text
            id       gen-id
            in       gen-optional-frames
            out      gen-optional-frames
            props    gen-property-map]
    (model/producer resource :id id :in in :out out :properties props)))

(def gen-entry
  (gen/let [id gen-id, in gen-optional-frames, out gen-optional-frames]
    (model/entry id :in in :out out)))

(def gen-blank (gen/fmap model/blank gen/nat))

(def gen-playlist
  (gen/let [id    gen-id
            items (gen/vector (gen/frequency [[4 gen-entry] [1 gen-blank]]) 0 4)]
    (model/playlist id items)))

(def gen-filter
  (gen/let [service gen-text, args gen-property-map
            in gen-optional-frames, out gen-optional-frames]
    (model/filter service args :in in :out out)))

(def gen-transition
  (gen/let [service gen-text, a gen-frames, b gen-frames, args gen-property-map
            in gen-optional-frames]
    (model/transition service a b args :in in)))

(def gen-track
  (gen/let [id   gen-id
            hide (gen/elements [nil :audio :video])]
    (model/track id :hide hide)))

(def gen-tractor
  (gen/let [id          gen-id
            tracks      (gen/vector gen-track 1 3)
            transitions (gen/vector gen-transition 0 2)
            filters     (gen/vector gen-filter 0 2)]
    (model/tractor id tracks :transitions transitions :filters filters)))

(def gen-profile
  (gen/let [width  (gen/choose 16 4096)
            height (gen/choose 16 4096)
            fps    (gen/elements [24 25 30 50 29.97 23.976 [30000 1001] [24 1]])
            sar    (gen/elements [[1 1] [4 3] [64 45]])
            dar    (gen/elements [[16 9] [4 3] [21 9]])]
    (model/profile :width width :height height :fps fps :sar sar :dar dar)))

(def gen-document
  "A whole <mlt> document: a profile, some producers, some playlists, a tractor
   -- the emission order real projects use."
  (gen/let [profile   gen-profile
            producers (gen/vector gen-producer 0 3)
            playlists (gen/vector gen-playlist 0 3)
            tractors  (gen/vector gen-tractor 0 1)]
    (apply model/document (concat [profile] producers playlists tractors))))

(def gen-escaping-document
  "Every draw carries a `\\\"` in an attribute value and a `<` in a text value:
   the two characters measured to be the only ones a round-trip can notice a
   missing escape for. Mutation runs on THIS generator so that a mutant which
   survives is evidence about the law, not about the seed."
  (gen/let [n     gen/nat
            props gen-property-map
            extra gen-text]
    (let [pid (str "id\"" n)
          pl  (str "pl\"" n)]
      (model/document
       (model/profile :width 320 :height 240 :fps 25)
       (model/producer (str "res<" extra) :id pid :properties props)
       (model/playlist pl [(model/entry pid) (model/blank 3)])
       (model/tractor (str "t\"" n) [(model/track pl)])))))

(def gen-nested-element
  "A raw nested element tree with escapable characters in attributes and text,
   exercising depth the document builders do not reach."
  (gen/recursive-gen
   (fn [inner]
     (gen/let [tag      (gen/fmap #(str "n" %) gen/nat)
               attrs    (gen/vector (gen/tuple (gen/fmap #(str "a" %) gen/nat)
                                               gen-attr-value)
                                    0 3)
               children (gen/vector inner 1 3)]
       (apply xml/element tag attrs children)))
   (gen/let [tag   (gen/fmap #(str "leaf" %) gen/nat)
             attrs (gen/vector (gen/tuple (gen/fmap #(str "a" %) gen/nat)
                                          gen-attr-value)
                               0 3)
             text  gen-text]
     (xml/element tag attrs text))))

;; --------------------------------------------------------------------- laws

(defn- round-trip
  "emit -> parse. Returns the parse answer so a failure shows the message."
  [node]
  (model/parse (model/emit node)))

(defn- value-law [node]
  (= node (:ok (round-trip node))))

(defn- byte-law [node]
  (let [out (model/emit node)]
    (= out (model/emit (:ok (model/parse out))))))

(defn- fixpoint-law
  "One round-trip is idempotent: whatever emit/parse lose, they lose once."
  [node]
  (let [once (:ok (round-trip node))]
    (= once (:ok (round-trip once)))))

(defspec document-round-trip-is-value-preserving 200
  (prop/for-all [doc gen-document] (value-law doc)))

(defspec document-round-trip-is-byte-stable 200
  (prop/for-all [doc gen-document] (byte-law doc)))

(defspec nested-elements-round-trip 200
  (prop/for-all [node gen-nested-element] (value-law node)))

(defspec round-trip-reaches-a-fixpoint-in-one-step 200
  (prop/for-all [doc gen-document] (fixpoint-law doc)))

;; Totality: a parser that throws on hostile input is a parser callers must
;; wrap. Bounded length keeps deep nesting away from the stack, which is an
;; Error rather than an Exception and so outside `parse`'s catch.
(def ^:private gen-xml-ish
  (gen/fmap str/join
            (gen/vector (gen/frequency
                         [[3 (gen/elements (vec "<>/=\"'& abc019"))]
                          [1 (gen/elements ["<a>" "</a>" "<?xml?>" "<!--x-->"
                                            "<![CDATA[y]]>" "&amp;" "&#10;"
                                            "&nosuch;" "<a k=\"v\""])]])
                        0 30)))

(defn- total-answer? [answer]
  (and (map? answer)
       (or (contains? answer :ok)
           (= :mlt/xml-malformed (:error answer)))))

(defspec parse-is-total-on-arbitrary-input 400
  (prop/for-all [s gen-xml-ish]
    (total-answer? (model/parse s))))

(defspec parse-is-total-on-truncated-valid-documents 200
  (prop/for-all [doc gen-document
                 n   (gen/choose 0 400)]
    (let [out (model/emit doc)]
      (total-answer? (model/parse (subs out 0 (min n (count out))))))))

;; ----------------------------------------------------------------- mutation

(def mutants
  "name -> var overrides that break emission. Each must FAIL the value law.
   The names say what wrong implementation each one encodes."
  {"attribute escaping dropped"
   {#'xml/escape-attr (fn [s] (str s))}

   "text escaping dropped"
   {#'xml/escape-text (fn [s] (str s))}

   "attributes emitted in sorted order rather than declaration order"
   {#'hive-kdenlive.mlt.xml/attrs->string
    (fn [attrs]
      (apply str (map (fn [[k v]] (str " " k "=\"" (xml/escape-attr v) "\""))
                      (sort-by first attrs))))}

   "adjacent text runs merged across an element boundary"
   {#'hive-kdenlive.mlt.xml/normalize-content
    (fn [content] (vec content))}})

(defn- law-survives?
  "Does the value law still pass under these overrides? 50 draws of
   `gen-escaping-document`, every one of which carries both load-bearing
   characters, so survival is a statement about the law."
  [overrides]
  (:pass? (with-redefs-fn overrides
            (fn [] (tc/quick-check 50 (prop/for-all [doc gen-escaping-document]
                                        (value-law doc)))))))

(deftest mutants-die-test
  (testing "the unmutated law passes, so a dead mutant means the mutation"
    (is (true? (law-survives? {}))))
  (doseq [[label overrides] mutants]
    (testing label
      (is (false? (law-survives? overrides))
          (str "MUTATION SURVIVED: '" label
               "' passed the round-trip law -- the generators do not"
               " discriminate it")))))

;; ------------------------------------------ what the laws do NOT constrain

(deftest escaping-is-under-constrained-test
  (testing "only \" (attributes) and < (text) are visible to a round-trip"
    (let [broke? (fn [overrides build]
                   (with-redefs-fn overrides
                     (fn []
                       (into {} (for [c [\& \< \> \" \' \newline \tab \return]]
                                  [c (not (value-law (build c)))])))))
          attr-break (broke? {#'xml/escape-attr (fn [s] (str s))}
                             #(model/document (model/producer "r" :id (str "a" % "b"))))
          text-break (broke? {#'xml/escape-text (fn [s] (str s))}
                             #(model/document (model/producer (str "a" % "b") :id "p")))]
      (is (= #{\"} (set (keep key (filter val attr-break))))
          "attribute escaping: a round-trip only notices a missing \" escape")
      (is (= #{\<} (set (keep key (filter val text-break))))
          "text escaping: a round-trip only notices a missing < escape")))
  (testing "the survivors still emit XML a conforming parser rejects"
    ;; A bare & is a well-formedness error; MLT's own parser refuses it. The
    ;; round-trip law cannot see that, because `unescape` keeps an unmatched &
    ;; verbatim. hive-kdenlive.oracle-test pins the bytes instead.
    (is (str/includes? (model/emit (model/producer "a&b")) "a&amp;b"))))

;; ------------------------------------------- the representable subset, pinned

(deftest non-string-attribute-values-are-byte-stable-but-not-value-stable-test
  (testing "the builders accept a number for :id; emission stringifies it"
    (let [doc (model/document (model/producer "color:red" :id 7))]
      (is (false? (value-law doc))
          "if this starts passing, the builders began coercing -- widen gen-id")
      (is (true? (byte-law doc)))
      (is (= "7" (xml/attr (first (xml/children (:ok (round-trip doc)) "producer"))
                           "id"))))))

(deftest empty-text-value-is-not-byte-stable-test
  (testing "a <property> with an empty value loses the empty text node"
    (let [doc  (model/document (model/producer "" :id "p0"))
          out  (model/emit doc)
          back (:ok (model/parse out))]
      (is (str/includes? out "<property name=\"resource\"></property>"))
      (is (str/includes? (model/emit back) "<property name=\"resource\"/>")
          "the re-emit self-closes: emit is NOT byte-stable here")
      (is (false? (value-law doc)))
      (is (false? (byte-law doc)))
      (testing "but it settles after one round-trip"
        (is (true? (fixpoint-law doc)))))))
