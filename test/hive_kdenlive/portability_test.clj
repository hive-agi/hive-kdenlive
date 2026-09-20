(ns hive-kdenlive.portability-test
  "The MLT document core and the route catalog are portable — JVM, cljw and
   cljrs — which is what lets a timeline be built as data on a host with no
   JVM. Today that holds by discipline alone: nothing fails if a boundary
   library is required from `mlt/` tomorrow.

   This is that check. Two invariants, read off the files rather than off
   loaded namespaces, because a namespace that requires a JVM library loads
   perfectly well on the JVM — which is exactly where the breakage hides:

     1. no .cljc REQUIRES or IMPORTS a host-only library;
     2. no .cljc contains a reader conditional.

   A portable core here is not `Clojure with #?(:clj ...) escapes`; it is
   plain Clojure that happens to run on three hosts, with everything
   host-specific pushed out to a boundary namespace — `render` (melt),
   `kdenlive.client` (HTTP) and `addon` (the MCP surface). hive-creator's
   production core holds the same line, and carries the same test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk])
  (:import (java.io File PushbackReader)))

(def banned-prefixes
  "Namespace prefixes that tie a file to one host."
  ["malli" "hive-addon" "hive-di" "clojure.java." "clojure.data.json"
   "taoensso.timbre" "org.httpkit" "java."])

(defn- portable-files []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile ^File %))
       (filter #(str/ends-with? (.getName ^File %) ".cljc"))
       (sort-by #(.getPath ^File %))))

(defn- ns-form [^File file]
  (with-open [r (PushbackReader. (io/reader file))]
    (read {:read-cond :preserve} r)))

(defn- referenced-namespaces
  "Every symbol named anywhere in the ns form, as strings."
  [form]
  (let [found (atom [])]
    (walk/postwalk (fn [x] (when (symbol? x) (swap! found conj (str x))) x) form)
    @found))

(defn- violations [^File file]
  (for [referenced (referenced-namespaces (ns-form file))
        banned     banned-prefixes
        :when      (str/starts-with? referenced banned)]
    {:referenced referenced :banned banned}))

(deftest the-portable-core-is-still-there-test
  (let [files (portable-files)]
    (is (seq files) "no .cljc under src — the portable MLT core has vanished")
    (is (<= 7 (count files))
        (str "the portable core shrank to " (count files) " files"))
    (testing "the route catalog is part of it, not only the MLT model"
      (is (some #(= "routes.cljc" (.getName ^File %)) files)
          "routes.cljc is what hive-creator plans against; it must stay portable"))))

(deftest no-portable-namespace-requires-a-host-only-library-test
  (doseq [^File file (portable-files)]
    (testing (.getName file)
      (is (= [] (vec (violations file)))
          (str (.getName file) " names a host-only library in its ns form."
               " If it belongs here, it belongs at a boundary instead"
               " (hive-kdenlive.render / kdenlive.client / addon)")))))

(deftest no-portable-namespace-hides-behind-a-reader-conditional-test
  (doseq [^File file (portable-files)]
    (testing (.getName file)
      (is (not (str/includes? (slurp file) "#?("))
          (str (.getName file) " contains a reader conditional. The portable"
               " core carries none: a host difference is a sign the code"
               " belongs at a boundary, not that it needs an escape hatch")))))

(deftest the-boundary-namespaces-stay-on-the-jvm-test
  (doseq [path ["src/hive_kdenlive/render.clj"
                "src/hive_kdenlive/addon.clj"
                "src/hive_kdenlive/kdenlive/client.clj"]]
    (is (.exists (io/file path)) (str path " is a boundary and must stay .clj"))
    (is (not (.exists (io/file (str/replace path ".clj" ".cljc"))))
        (str path " became .cljc; it drives melt, HTTP or the MCP surface"
             " and cannot be portable"))))

(deftest the-check-would-actually-catch-a-violation-test
  (testing "a ns form that requires an HTTP client is caught"
    (let [tmp (File/createTempFile "portability" ".cljc")]
      (try
        (spit tmp "(ns probe (:require [org.httpkit.client :as http] [clojure.string :as str]))\n")
        (is (= [{:referenced "org.httpkit.client" :banned "org.httpkit"}]
               (vec (violations tmp))))
        (finally (.delete tmp)))))
  (testing "a ns form that only names portable things is clean"
    (let [tmp (File/createTempFile "portability" ".cljc")]
      (try
        (spit tmp "(ns probe (:require [clojure.string :as str]))\n")
        (is (= [] (vec (violations tmp))))
        (finally (.delete tmp))))))
