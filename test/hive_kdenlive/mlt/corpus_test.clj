(ns hive-kdenlive.mlt.corpus-test
  "mlt.project against real Kdenlive files: every .kdenlive in the fork's test
   dataset (kdenlive/tests/dataset of a kdenlive-mcp checkout) must parse, and
   where it has a profile its duration must agree with the length Kdenlive
   itself recorded. Point KDENLIVE_FORK at the checkout; skips when unset,
   like catalog-sync-test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.mlt.project :as project]
            [hive-kdenlive.mlt.time :as time]
            [hive-kdenlive.mlt.xml :as xml]))

(def ^:private dataset
  (some-> (System/getenv "KDENLIVE_FORK") (io/file "kdenlive/tests/dataset")))

(defn- recorded-length
  "The timeline length Kdenlive wrote into the file, in frames: the out of the
   tractor marked kdenlive:projectTractor, or, in files older than that
   property, the longest out among the tractors that hold playlists (the
   timeline rows; the root also spans the long black track). nil if neither."
  [mlt fps]
  (let [tractors (xml/children mlt "tractor")
        prop?    (fn [t k] (some #(= k (xml/attr % "name")) (xml/children t "property")))
        playlists (into #{} (map #(xml/attr % "id")) (xml/children mlt "playlist"))
        rows     (filter (fn [t] (some #(playlists (xml/attr % "producer")) (xml/children t "track"))) tractors)
        len      (fn [t] (some-> (xml/attr t "out") (time/clock->frames fps) inc))]
    (if-let [pt (first (filter #(prop? % "kdenlive:projectTractor") tractors))]
      (len pt)
      (reduce max 0 (keep len rows)))))

(deftest every-dataset-project-parses-and-knows-its-length
  (if-not (and dataset (.isDirectory ^java.io.File dataset))
    (is true "KDENLIVE_FORK unset or has no kdenlive/tests/dataset: skipped")
    (let [files (sort-by #(.getName ^java.io.File %)
                         (filter #(str/ends-with? (.getName ^java.io.File %) ".kdenlive")
                                 (.listFiles ^java.io.File dataset)))]
      (is (<= 8 (count files)) "non-vacuity: the dataset has its .kdenlive files")
      (doseq [^java.io.File f files]
        (testing (.getName f)
          (let [text (slurp f)
                {:keys [ok error] :as res} (project/summarize text)]
            (is (nil? error) (pr-str res))
            (when ok
              (is (seq (:bin ok)) "a bin")
              (is (seq (:tracks ok)) "a timeline")
              (when-let [fps (get-in ok [:profile :fps])]
                (let [ours     (project/duration-frames ok)
                      recorded (recorded-length (:ok (xml/parse text)) fps)]
                  (is (some? ours))
                  ;; Kdenlive's project tractor is sometimes one frame past the
                  ;; last clip (test-missing, test-nesting-effects), sometimes not
                  (is (<= 0 (- recorded ours) 1) (str "ours " ours ", Kdenlive recorded " recorded)))))))))))
