(ns hive-kdenlive.mlt.xml-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.mlt.xml :as xml]))

(defn- emit-fragment
  "`emit` without the XML declaration."
  [node]
  (->> (str/split-lines (xml/emit node)) rest (str/join "\n")))

(deftest builders-test
  (let [n (xml/element "producer" [["id" "p0"] ["in" "00:00:00.000"] ["skip" nil]]
            (xml/element "property" [["name" "resource"]] "clip.mp4")
            nil)]
    (is (= "p0" (xml/attr n "id")))
    (is (nil? (xml/attr n "skip")) "nil-valued attrs are dropped")
    (is (= 1 (count (xml/children n))))
    (is (= 1 (count (xml/children n "property"))))
    (is (= 0 (count (xml/children n "filter"))))
    (is (= "clip.mp4" (xml/text (first (xml/children n)))))))

(deftest emit-escapes-test
  (testing "attribute escaping"
    (is (= "<a k=\"a&amp;b &lt;c&gt; &quot;d&quot; &#10;\"/>"
           (emit-fragment (xml/element "a" [["k" "a&b <c> \"d\" \n"]]))))
    (is (= "<a k=\"x\"/>"
           (emit-fragment (xml/element "a" [["k" "x"]])))))
  (testing "text escaping"
    (is (= "<a>1 &lt; 2 &amp; 3 &gt; 2</a>"
           (emit-fragment (xml/element "a" [] "1 < 2 & 3 > 2"))))))

(deftest emit-determinism-test
  (let [n     (xml/element "mlt" [["LC_NUMERIC" "C"] ["version" "7.0.0"]]
                (xml/element "profile" [["width" "1920"] ["height" "1080"]]))
        out   (xml/emit n)
        lines (str/split-lines out)]
    (is (= "<?xml version=\"1.0\" encoding=\"utf-8\"?>" (first lines)))
    (is (= "<mlt LC_NUMERIC=\"C\" version=\"7.0.0\">" (second lines))
        "attribute order is preserved, not sorted")
    (is (str/ends-with? out "\n"))))

(deftest round-trip-test
  (let [doc (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                 "<mlt LC_NUMERIC=\"C\" version=\"7.0.0\">\n"
                 " <profile width=\"1920\" height=\"1080\"/>\n"
                 " <producer id=\"p0\">\n"
                 "  <property name=\"resource\">a &amp; b.mp4</property>\n"
                 " </producer>\n"
                 "</mlt>\n")
        {:keys [ok]} (xml/parse doc)]
    (is ok "parses")
    (is (= doc (xml/emit ok)) "emit(parse(doc)) = doc for canonical input")
    (is (= "a & b.mp4"
           (xml/text (first (xml/children (first (xml/children ok "producer")) "property"))))
        "entities decode on parse")))

(deftest parse-constructs-test
  (testing "comments, CDATA, DOCTYPE, self-closing"
    (let [{:keys [ok]} (xml/parse
                        (str "<!DOCTYPE mlt>\n"
                             "<!-- leading -->\n"
                             "<mlt><!-- inner --><p><![CDATA[a <b> & c]]></p><e/></mlt>"))]
      (is ok)
      (is (= "a <b> & c" (xml/text (first (xml/children ok "p")))))
      (is (= [] (:content (first (xml/children ok "e")))))))
  (testing "numeric entities"
    (is (= "A" (xml/unescape "&#65;")))
    (is (= "A" (xml/unescape "&#x41;")))
    (is (= "&bogus;" (xml/unescape "&bogus;")) "unknown entities kept verbatim")
    (is (= "&amp" (xml/unescape "&amp")) "unterminated entities kept verbatim")))

(deftest malformed-test
  (doseq [[label input] {"not a string"        42
                         "mismatched close"    "<a></b>"
                         "unclosed element"    "<a>"
                         "trailing content"    "<a/> junk"
                         "unquoted attr"       "<a k=v/>"
                         "unterminated attr"   "<a k=\"v>"}]
    (testing label
      (let [{:keys [error at message]} (xml/parse input)]
        (is (= :mlt/xml-malformed error))
        (is (integer? at))
        (is (string? message))))))
