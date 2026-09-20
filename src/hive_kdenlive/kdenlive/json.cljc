(ns hive-kdenlive.kdenlive.json
  "JSON, both directions, with no dependency.

   hive-kdenlive deliberately carries no JSON library, and until 2026-09-20 it
   showed: the encoder handled a FLAT map of scalars and rendered anything
   else with `str`, so `:paths [\"a.mp4\" \"b.mp3\"]` went out as the Clojure
   literal `[\"a.mp4\" \"b.mp3\"]` (no commas) and `:params {:duration 5}` as
   `{:duration 5}`. Both are the shapes hive-creator's :finish actually sends,
   to :media/import and :clip/append-effect. There was no decoder at all, so
   every answer came back as a raw string and a caller that needed an id out
   of it got nothing.

   Neither had been noticed because the HTTP transport was never exercised end
   to end; the headless document transport was.

   `write` and `read` live together so a round trip can be property-tested,
   which is the only way a hand-written codec stays honest.

   Portable: clojure.core and clojure.string, and no host interop. There is no
   StringBuilder here and no `(int \\c)`, because neither exists on cljw or
   cljrs.

   `read` answers Clojure data with STRING keys, exactly as the wire spells
   them, because the route catalog's :result names a wire field."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; =============================================================================
;; write
;; =============================================================================

(def ^:private escapes
  {\" "\\\"" \\ "\\\\" \newline "\\n" \return "\\r" \tab "\\t"
   \formfeed "\\f" \backspace "\\b"})

(defn- escape
  "JSON string escaping, built with `str` rather than a StringBuilder: this
   namespace is .cljc and a host that is not the JVM has no StringBuilder."
  [s]
  (apply str (map (fn [c] (or (escapes c) c)) (str s))))

(defn write
  "`x` as a JSON string. Maps, vectors, sets, strings, keywords, numbers,
   booleans and nil. A keyword is written as its name, so :binId is \"binId\"
   and the fork's camelCase survives."
  [x]
  (cond
    (nil? x)        "null"
    (string? x)     (str "\"" (escape x) "\"")
    (keyword? x)    (str "\"" (escape (name x)) "\"")
    (symbol? x)     (str "\"" (escape (name x)) "\"")
    (boolean? x)    (str x)
    (number? x)     (str x)
    (map? x)        (str "{"
                         (clojure.string/join
                          ","
                          (map (fn [[k v]]
                                 (str "\"" (escape (if (keyword? k) (name k) (str k))) "\":"
                                      (write v)))
                               x))
                         "}")
    (coll? x)       (str "[" (clojure.string/join "," (map write x)) "]")
    :else           (str "\"" (escape x) "\"")))

;; =============================================================================
;; read
;; =============================================================================

(def ^:private hex-digits
  (into {} (map-indexed (fn [i c] [c i]))
        [\0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \a \b \c \d \e \f]))

(defn- hex4 [s i]
  (reduce (fn [acc k]
            (let [c (get s (+ i k))
                  d (or (hex-digits c) (hex-digits (first (clojure.string/lower-case (str c)))))]
              (if d (+ (* 16 acc) d) (reduced nil))))
          0 (range 4)))

(defn- ws? [c]
  (or (= c \space) (= c \tab) (= c \newline) (= c \return)))

(defn- skip-ws [s i]
  (loop [i i] (if (and (< i (count s)) (ws? (nth s i))) (recur (inc i)) i)))

(def ^:private digits #{\0 \1 \2 \3 \4 \5 \6 \7 \8 \9})

(defn- digit?
  "A set membership rather than an `int` comparison: `(int \\0)` is not
   portable across the hosts this namespace targets."
  [c]
  (contains? digits c))

(declare read-value)

(def ^:private unescapes
  {\" \" \\ \\ \/ \/ \n \newline \r \return \t \tab \b \backspace \f \formfeed})

(defn- read-string*
  "`i` points at the opening quote. Answers [string next-index], or [nil nil]
   when the string never closes or carries an escape JSON does not define.

   Characters are gathered into a vector and joined, not appended to a
   StringBuilder: this namespace is .cljc."
  [s i]
  (loop [i (inc i) acc []]
    (let [c (get s i)]
      (cond
        (nil? c) [nil nil]
        (= c \") [(apply str acc) (inc i)]
        (= c \\) (let [e (get s (inc i))]
                   (cond
                     (= e \u) (if-let [cp (hex4 s (+ i 2))]
                                (recur (+ i 6) (conj acc (char cp)))
                                [nil nil])
                     (contains? unescapes e) (recur (+ i 2) (conj acc (unescapes e)))
                     :else [nil nil]))
        :else (recur (inc i) (conj acc c))))))

(defn- read-number [s i]
  (let [end (loop [j i]
              (let [c (get s j)]
                (if (and c (or (digit? c) (= c \-) (= c \+) (= c \.) (= c \e) (= c \E)))
                  (recur (inc j))
                  j)))
        text (subs s i end)]
    (if (or (clojure.string/includes? text ".")
            (clojure.string/includes? text "e")
            (clojure.string/includes? text "E"))
      [(parse-double text) end]
      [(parse-long text) end])))

(defn- read-array [s i]
  (loop [i (skip-ws s (inc i)) acc []]
    (cond
      (= (get s i) \]) [acc (inc i)]
      :else (let [[v j] (read-value s i)]
              (if (nil? j)
                [nil nil]
                (let [j (skip-ws s j)]
                  (cond
                    (= (get s j) \,) (recur (skip-ws s (inc j)) (conj acc v))
                    (= (get s j) \]) [(conj acc v) (inc j)]
                    :else            [nil nil])))))))

(defn- read-object [s i]
  (loop [i (skip-ws s (inc i)) acc {}]
    (cond
      (= (get s i) \}) [acc (inc i)]
      (not= (get s i) \") [nil nil]
      :else
      (let [[k j] (read-string* s i)]
        (if (nil? j)
          [nil nil]
          (let [j (skip-ws s j)]
            (if (not= (get s j) \:)
              [nil nil]
              (let [[v j] (read-value s (skip-ws s (inc j)))]
                (if (nil? j)
                  [nil nil]
                  (let [j (skip-ws s j)]
                    (cond
                      (= (get s j) \,) (recur (skip-ws s (inc j)) (assoc acc k v))
                      (= (get s j) \}) [(assoc acc k v) (inc j)]
                      :else            [nil nil])))))))))))

(defn- read-value [s i]
  (let [i (skip-ws s i)
        c (get s i)]
    (cond
      (nil? c)  [nil nil]
      (= c \{)  (read-object s i)
      (= c \[)  (read-array s i)
      (= c \")  (read-string* s i)
      (or (digit? c) (= c \-)) (read-number s i)
      (= "true"  (subs s i (min (count s) (+ i 4)))) [true (+ i 4)]
      (= "false" (subs s i (min (count s) (+ i 5)))) [false (+ i 5)]
      (= "null"  (subs s i (min (count s) (+ i 4)))) [nil (+ i 4)]
      :else [nil nil])))

(defn read
  "Parse `s` as JSON. Answers {:ok value} or {:error :json/unparseable}.

   Object keys stay STRINGS, spelled as the wire spells them: the route
   catalog's :result names a wire field, and keywordizing here would make the
   catalog and the answer disagree."
  [s]
  (if (or (nil? s) (not (string? s)))
    {:error :json/unparseable :reason :not-a-string}
    (let [[v i] (read-value s 0)]
      (if (nil? i)
        {:error :json/unparseable :at 0 :body (subs s 0 (min 200 (count s)))}
        (let [i (skip-ws s i)]
          (if (< i (count s))
            {:error :json/trailing-input :at i}
            {:ok v}))))))
