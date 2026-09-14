(ns hive-kdenlive.kdenlive.document-integration-test
  "The :document transport end to end, with real melt and no Kdenlive: media
   probed by melt, a timeline built through catalog route ids, rendered by
   melt, and the output read back by ffprobe and ffmpeg, which are not MLT.

   Pixels are checked, not only frame counts. A render with the right number of
   frames and the wrong picture has already happened with these documents: a
   blank with no background track renders white.

   Skips (with an assertion saying so) when melt, ffprobe or ffmpeg is absent."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.kdenlive.client :as client]
            [hive-kdenlive.kdenlive.document :as document]
            [hive-kdenlive.render :as render]))

(defn- sh [& argv]
  (let [proc (.start (ProcessBuilder. ^java.util.List (vec argv)))
        out  (future (slurp (.getInputStream proc)))
        err  (future (slurp (.getErrorStream proc)))
        exit (.waitFor proc)]
    {:exit exit :out @out :err @err}))

(defn- colour-clip!
  "A FRAMES-long clip of COLOUR at PATH, rendered by melt."
  [melt path colour frames]
  (sh melt (str "color:" colour) (str "out=" (dec frames))
      "-consumer" (str "avformat:" path) "vcodec=mpeg4" "an=1" "f=matroska"))

(defn- frame-count [path]
  (parse-long (str/trim (:out (sh "ffprobe" "-v" "error" "-count_frames" "-select_streams" "v:0"
                                  "-show_entries" "stream=nb_read_frames" "-of" "csv=p=0" path)))))

(defn- pixel
  "[r g b] of frame N of PATH, averaged to one pixel."
  [path n]
  (let [proc (.start (ProcessBuilder. ^java.util.List
                                     ["ffmpeg" "-v" "error" "-i" path "-vf" (str "select=eq(n\\," n "),scale=1:1")
                                      "-fps_mode" "passthrough" "-frames:v" "1"
                                      "-f" "rawvideo" "-pix_fmt" "rgb24" "-"]))
        bytes (.readAllBytes (.getInputStream proc))]
    (.waitFor proc)
    (mapv #(bit-and % 0xff) (take 3 bytes))))

(defn- colour
  "Which of red, green, black a pixel shows, or :other.

   Classified by dominant channel, not by distance from 255: the timeline's
   default profile is 1080p with colorspace 709, and ffmpeg decodes the
   untagged output with the BT.601 matrix, so a full red reads back as
   (216,0,0). Measured 2026-09-13. That is a property of how the file is
   DECODED, not of what the timeline placed, and this test is about the latter."
  [[r g b]]
  (cond
    (and (< r 40) (< g 40) (< b 40)) :black
    (and (> r 160) (< g 40) (< b 40)) :red
    (and (> g 160) (< r 40) (< b 40)) :green
    :else :other))

(deftest ^:integration a-timeline-built-by-route-renders-what-it-says
  (let [melt (render/which "melt")]
    (if-not (and melt (render/which "ffprobe") (render/which "ffmpeg"))
      (is true "melt, ffprobe or ffmpeg not on PATH: skipped")
      (let [dir   (doto (io/file (System/getProperty "java.io.tmpdir") (str "hive-kdenlive-doc-" (System/nanoTime))) .mkdirs)
            red   (str dir "/red.mkv")
            green (str dir "/green.mkv")
            proj  (str dir "/edit.hkd.edn")
            out   (str dir "/out.mkv")
            _     (colour-clip! melt red "#ff0000" 50)
            _     (colour-clip! melt green "#00ff00" 30)
            k     (document/document-kdenlive proj)
            call  (fn [route params]
                    (let [{:keys [ok error] :as res} (client/-call k route params)]
                      (is (nil? error) (pr-str route params res))
                      ok))]
        (testing "melt probes the lengths the timeline will trust"
          (let [{[r g] :ids} (call :media/import {:paths [red green]})
                {v :id}      (call :timeline/add-track {:name "V1" :isAudio false})]
            (is (= [50 30] (mapv :length (:clips (call :media/list {})))))
            (call :timeline/insert-clip {:binId r :trackId v :position 0})
            (call :timeline/insert-clip {:binId g :trackId v :position 60})
            (testing "space opened after the first clip moves the second"
              (call :timeline/insert-space {:trackId v :position 55 :duration 5})
              (is (= [[0 50] [65 30]]
                     (mapv (juxt :position :duration) (:clips (call :timeline/track-clips {:id v}))))))
            (is (= 95 (:duration (call :project/info {}))))))
        (testing "the project and its MLT document are on disk"
          (is (.isFile (io/file proj)))
          (is (str/includes? (slurp (document/mlt-path proj)) "black_track")))
        (testing "a route with no headless meaning is refused, not ignored"
          (is (= :document/unsupported-route (:error (client/-call k :playback/play {})))))
        (testing "melt renders it, and ffmpeg sees what the timeline says"
          (let [res (call :render/start {:outputFile out})]
            (is (= 95 (:frames res)))
            (is (= 95 (frame-count out)))
            (is (= :red (colour (pixel out 10))) (str "frame 10 should be red: " (pixel out 10)))
            (is (= :black (colour (pixel out 57))) (str "frame 57 is the gap, black: " (pixel out 57)))
            (is (= :green (colour (pixel out 80))) (str "frame 80 should be green: " (pixel out 80)))))
        (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))))
