(ns hive-kdenlive.mlt.xml
  "XML text <-> element tree, written by hand over strings.

   Node shape: {:tag string :attrs [[name value] ...] :content [node-or-string ...]}.
   Attribute order is a vector so emission is byte-deterministic on every host.

   `emit` : node -> string (declaration, one-space indent, trailing newline).
   `parse`: string -> {:ok node} | {:error :mlt/xml-malformed :at int :message string}.

   Portable: clojure.core and clojure.string only, no reader conditionals."
  (:require [clojure.string :as str]))

(defn element
  "Build a node. `attrs` is a vector of [name value] pairs; nil values are dropped."
  [tag attrs & content]
  {:tag     tag
   :attrs   (vec (remove (fn [[_ v]] (nil? v)) attrs))
   :content (vec (remove nil? content))})

(defn attr
  "Value of attribute `k` on `node`, or nil."
  [node k]
  (some (fn [[ak av]] (when (= ak k) av)) (:attrs node)))

(defn children
  "Element children of `node` with tag `tag` (all element children when tag is nil)."
  ([node] (filterv map? (:content node)))
  ([node tag] (filterv #(and (map? %) (= tag (:tag %))) (:content node))))

(defn text
  "Concatenated text content of `node`."
  [node]
  (apply str (filter string? (:content node))))

;; ---------------------------------------------------------------------------
;; Escaping

(def ^:private attr-escapes
  {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \newline "&#10;" \tab "&#9;" \return "&#13;"})

(def ^:private text-escapes
  {\& "&amp;" \< "&lt;" \> "&gt;"})

(defn escape-attr [s] (str/escape (str s) attr-escapes))

(defn escape-text [s] (str/escape (str s) text-escapes))

(def ^:private named-entities
  {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'"})

(def ^:private hex-digits "0123456789abcdef")

(defn- digits->int
  [digits radix]
  (reduce (fn [acc c]
            (let [d (str/index-of hex-digits (str/lower-case (str c)))]
              (when (and acc d (< d radix))
                (+ (* acc radix) d))))
          0
          digits))

(defn- code-point->string
  "Only BMP, non-surrogate code points: hosts disagree on chars outside it."
  [cp]
  (when (and cp (or (< 0 cp 0xD800) (< 0xDFFF cp 0x10000)))
    (str (char cp))))

(defn- decode-entity
  [body]
  (cond
    (contains? named-entities body) (get named-entities body)
    (str/starts-with? body "#x") (code-point->string (digits->int (subs body 2) 16))
    (str/starts-with? body "#") (code-point->string (digits->int (subs body 1) 10))
    :else nil))

(defn unescape
  "Decode XML entities in `s`. An unknown or unterminated entity is kept verbatim."
  [s]
  (if-not (str/index-of s "&")
    s
    (loop [i 0 acc []]
      (let [amp (str/index-of s "&" i)]
        (if-not amp
          (apply str (conj acc (subs s i)))
          (let [semi    (str/index-of s ";" amp)
                decoded (when (and semi (< (- semi amp) 12))
                          (decode-entity (subs s (inc amp) semi)))]
            (if decoded
              (recur (inc semi) (conj acc (subs s i amp) decoded))
              (recur (inc amp) (conj acc (subs s i (inc amp)))))))))))

;; ---------------------------------------------------------------------------
;; Emit

(defn- attrs->string
  [attrs]
  (apply str (map (fn [[k v]] (str " " k "=\"" (escape-attr v) "\"")) attrs)))

(defn- emit-node
  [node depth]
  (let [pad     (apply str (repeat depth " "))
        open    (str pad "<" (:tag node) (attrs->string (:attrs node)))
        content (:content node)]
    (cond
      (empty? content)
      [(str open "/>")]

      (every? string? content)
      [(str open ">" (escape-text (apply str content)) "</" (:tag node) ">")]

      :else
      (concat [(str open ">")]
              (mapcat (fn [c]
                        (if (string? c)
                          [(str pad " " (escape-text c))]
                          (emit-node c (inc depth))))
                      content)
              [(str pad "</" (:tag node) ">")]))))

(defn emit
  "Serialize a node tree to an XML document string."
  [node]
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
       (str/join "\n" (emit-node node 0))
       "\n"))

;; ---------------------------------------------------------------------------
;; Parse

(defn- fail
  [at message]
  (throw (ex-info message {::at at ::message message})))

(defn- whitespace? [c] (contains? #{\space \tab \newline \return} c))

(defn- char-at [s i] (when (< i (count s)) (nth s i)))

(defn- skip-ws
  [s i]
  (loop [i i]
    (if (whitespace? (char-at s i)) (recur (inc i)) i)))

(defn- skip-past
  [s i token]
  (if-let [j (str/index-of s token i)]
    (+ j (count token))
    (fail i (str "unterminated construct, expected " token))))

(defn- name-char?
  [c]
  (and c (not (whitespace? c)) (not (contains? #{\/ \> \= \< \" \'} c))))

(defn- read-name
  [s i]
  (let [end (loop [j i] (if (name-char? (char-at s j)) (recur (inc j)) j))]
    (when (= end i) (fail i "expected a name"))
    [(subs s i end) end]))

(defn- read-attrs
  [s i]
  (loop [i i attrs []]
    (let [i (skip-ws s i)
          c (char-at s i)]
      (cond
        (nil? c) (fail i "unexpected end inside a tag")
        (= c \>) [attrs (inc i) false]
        (and (= c \/) (= (char-at s (inc i)) \>)) [attrs (+ i 2) true]
        :else
        (let [[k j] (read-name s i)
              j     (skip-ws s j)
              _     (when-not (= (char-at s j) \=) (fail j (str "expected = after attribute " k)))
              j     (skip-ws s (inc j))
              q     (char-at s j)
              _     (when-not (contains? #{\" \'} q) (fail j "expected a quoted attribute value"))
              close (str/index-of s (str q) (inc j))
              _     (when-not close (fail j "unterminated attribute value"))]
          (recur (inc close) (conj attrs [k (unescape (subs s (inc j) close))])))))))

(defn- normalize-content
  [content]
  (let [merged (reduce (fn [acc c]
                         (if (and (string? c) (string? (peek acc)))
                           (conj (pop acc) (str (peek acc) c))
                           (conj acc c)))
                       []
                       content)]
    (if (some map? merged)
      (filterv #(not (and (string? %) (str/blank? %))) merged)
      merged)))

(declare read-element)

(defn- read-content
  [s i tag]
  (loop [i i content []]
    (let [c (char-at s i)]
      (cond
        (nil? c)
        (fail i (str "unclosed element " tag))

        (str/starts-with? (subs s i (min (count s) (+ i 2))) "</")
        (let [[close j] (read-name s (+ i 2))
              j         (skip-ws s j)]
          (when-not (= close tag) (fail i (str "mismatched close tag " close " for " tag)))
          (when-not (= (char-at s j) \>) (fail j "expected > in close tag"))
          [(normalize-content content) (inc j)])

        (str/starts-with? (subs s i (min (count s) (+ i 4))) "<!--")
        (recur (skip-past s i "-->") content)

        (str/starts-with? (subs s i (min (count s) (+ i 9))) "<![CDATA[")
        (let [end (str/index-of s "]]>" i)]
          (when-not end (fail i "unterminated CDATA"))
          (recur (+ end 3) (conj content (subs s (+ i 9) end))))

        (= c \<)
        (let [[child j] (read-element s i)]
          (recur j (conj content child)))

        :else
        (let [end (or (str/index-of s "<" i) (count s))]
          (recur end (conj content (unescape (subs s i end)))))))))

(defn- read-element
  [s i]
  (when-not (= (char-at s i) \<) (fail i "expected <"))
  (let [[tag j]              (read-name s (inc i))
        [attrs j self-close] (read-attrs s j)]
    (if self-close
      [{:tag tag :attrs attrs :content []} j]
      (let [[content j] (read-content s j tag)]
        [{:tag tag :attrs attrs :content content} j]))))

(defn- skip-prolog
  [s i]
  (loop [i (skip-ws s i)]
    (let [head (subs s i (min (count s) (+ i 9)))]
      (cond
        (str/starts-with? head "<?")   (recur (skip-ws s (skip-past s i "?>")))
        (str/starts-with? head "<!--") (recur (skip-ws s (skip-past s i "-->")))
        (str/starts-with? head "<!DOCTYPE") (recur (skip-ws s (skip-past s i ">")))
        :else i))))

(defn parse
  "Parse an XML document string into a node tree."
  [s]
  (if-not (string? s)
    {:error :mlt/xml-malformed :at 0 :message "input is not a string"}
    (try
      (let [i        (skip-prolog s 0)
            [node j] (read-element s i)
            rest-at  (skip-prolog s j)]
        (if (< rest-at (count s))
          {:error :mlt/xml-malformed :at rest-at :message "content after the root element"}
          {:ok node}))
      (catch Exception e
        (let [data (ex-data e)]
          (if (contains? data ::at)
            {:error :mlt/xml-malformed :at (::at data) :message (::message data)}
            {:error :mlt/xml-malformed :at -1 :message (str "parse failed: " (ex-message e))}))))))
