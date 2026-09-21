(ns hive-kdenlive.render-integration-test
  "End-to-end against the real melt binary. Skips when melt is not on PATH;
   excluded from the unit suite via the :integration keyword."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.mlt.model :as model]
            [hive-kdenlive.render :as render]
            [clojure.string :as str]))

(deftest ^:integration real-melt-render-test
  (if-let [bin (render/which "melt")]
    (let [out (str (System/getProperty "java.io.tmpdir")
                   "/hive-kdenlive-it-" (System/nanoTime) ".mp4")
          doc (model/document
               (model/profile :width 320 :height 240 :fps 25)
               (model/producer "color:red" :id "p0" :length "25")
               (model/playlist "pl0" [(model/entry "p0")])
               (model/tractor "tractor0" [(model/track "pl0")]))
          res (binding [render/*renderer* (render/melt-renderer bin)]
                (render/render-doc! doc out))]
      (is (:ok res) (str "melt failed: " res))
      (is (.exists (java.io.File. out)))
      (.delete (java.io.File. out)))
    (is true "melt not on PATH — skipped")))

(defn- ffprobe
  "ffprobe's compact answer for ENTRIES of PATH's first video stream and format."
  [path entries]
  (let [proc (.start (ProcessBuilder. ^java.util.List
                                     ["ffprobe" "-v" "error" "-select_streams" "v:0"
                                      "-show_entries" entries "-of" "default=nw=1" path]))
        out  (future (slurp (.getInputStream proc)))]
    (.waitFor proc)
    (into {} (map (fn [l] (vec (str/split l #"=" 2)))) (str/split-lines @out))))

(deftest ^:integration consumer-options-reach-the-encoder
  (if-not (and (render/which "melt") (render/which "ffprobe"))
    (is true "melt or ffprobe not on PATH: skipped")
    (let [dir   (doto (java.io.File. (System/getProperty "java.io.tmpdir") (str "hive-kdenlive-consumer-" (System/nanoTime))) .mkdirs)
          at    (fn [f] (str dir "/" f))
          ;; noise does not compress, so a bitrate cap shows in the file
          doc   (model/document
                 (model/profile :width 320 :height 240 :fps 25)
                 (model/producer "noise" :id "p0" :in "0" :out "49" :properties {"mlt_service" "noise" "length" "50"})
                 (model/playlist "pl0" [(model/entry "p0" :in "0" :out "49")])
                 (model/tractor "tractor0" [(model/track "pl0")]))
          go!   (fn [f consumer]
                  (let [res (binding [render/*renderer* (render/melt-renderer)]
                              (render/render-doc! doc (at f) :consumer consumer))]
                    (is (:ok res) (pr-str res))
                    (ffprobe (at f) "stream=codec_name,pix_fmt:format=bit_rate")))
          rate  (fn [m] (parse-long (get m "bit_rate")))]
      (testing "vcodec picks the encoder, as a map or as a preset string"
        (is (= "mpeg4" (get (go! "a.mkv" {:vcodec "mpeg4" :an 1}) "codec_name")))
        (is (= "h264" (get (go! "b.mkv" "vcodec=libx264 an=1") "codec_name"))))
      (testing "vb caps the video bitrate"
        (let [lo (rate (go! "lo.mkv" {:vcodec "libx264" :vb "200k" :an 1}))
              hi (rate (go! "hi.mkv" {:vcodec "libx264" :vb "2M" :an 1}))]
          (is (< lo 1000000) (str "200k asked, " lo " b/s"))
          (is (< (* 3 lo) hi) (str lo " vs " hi))))
      (testing "crf reaches libx264"
        (is (< (* 20 (rate (go! "q45.mkv" {:vcodec "libx264" :crf 45 :an 1})))
               (rate (go! "q10.mkv" {:vcodec "libx264" :crf 10 :an 1})))))
      (testing "a bad option is refused and nothing is written"
        (is (= :render/bad-consumer-option (:error (render/render-doc! doc (at "x.mkv") :consumer "vcodec"))))
        (is (not (.exists (java.io.File. ^String (at "x.mkv"))))))
      (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f)))))
