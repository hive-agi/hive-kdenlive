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

(defn- rgb-at
  "[r g b] of pixel (X, Y) in the frame at SECONDS of PATH. Converted to RGB
   before the crop: a 1x1 crop of a 4:2:0 frame yields no output at all."
  [path seconds x y]
  (let [proc  (.start (ProcessBuilder. ^java.util.List
                                      ["ffmpeg" "-v" "error" "-ss" (str seconds) "-i" path "-frames:v" "1"
                                       "-vf" (str "format=rgb24,crop=1:1:" x ":" y) "-f" "rawvideo" "-pix_fmt" "rgb24" "-"]))
        bytes (.readAllBytes (.getInputStream proc))]
    (.waitFor proc)
    (mapv #(bit-and % 0xff) (take 3 bytes))))

(defn- brightest-in-band
  "The largest min(r,g,b) in the horizontal band [Y, Y+H) of the frame at
   SECONDS: near 255 only where something white, like title text, is drawn."
  [path seconds y h]
  (let [proc  (.start (ProcessBuilder. ^java.util.List
                                      ["ffmpeg" "-v" "error" "-ss" (str seconds) "-i" path "-frames:v" "1"
                                       "-vf" (str "crop=iw:" h ":0:" y) "-f" "rawvideo" "-pix_fmt" "rgb24" "-"]))
        bytes (.readAllBytes (.getInputStream proc))]
    (.waitFor proc)
    (reduce max 0 (map (fn [[r g b]] (min r g b))
                       (partition 3 (map #(bit-and % 0xff) bytes))))))

(defn- tone-level
  "Mean volume in dB of the FREQ Hz band of PATH's audio over [FROM, FROM+DUR) s."
  [path freq from dur]
  (let [{:keys [err]} (sh "ffmpeg" "-hide_banner" "-ss" (str from) "-t" (str dur) "-i" path "-vn"
                          "-af" (str "bandpass=f=" freq ":w=40,volumedetect") "-f" "null" "-")]
    (some-> (re-find #"mean_volume: (-?[0-9.]+) dB" err) second parse-double)))

(defn- probe-stream [path entries]
  (str/trim (:out (sh "ffprobe" "-v" "error" "-select_streams" "v:0" "-show_entries" (str "stream=" entries)
                      "-of" "default=nw=1:nk=1" path))))

(deftest ^:integration layers-titles-fades-and-audio-render-as-composed
  (let [melt (render/which "melt")]
    (if-not (and melt (render/which "ffprobe") (render/which "ffmpeg"))
      (is true "melt, ffprobe or ffmpeg not on PATH: skipped")
      (let [dir  (doto (io/file (System/getProperty "java.io.tmpdir") (str "hive-kdenlive-layers-" (System/nanoTime))) .mkdirs)
            red  (str dir "/red.mp4")
            sq   (str dir "/square.png")
            tone (str dir "/tone.wav")
            out  (str dir "/out.mp4")
            _    (sh "ffmpeg" "-y" "-f" "lavfi" "-i" "color=c=red:s=1080x1920:r=30:d=3" "-f" "lavfi" "-i" "sine=f=440:d=3"
                     "-c:v" "libx264" "-pix_fmt" "yuv420p" "-c:a" "aac" "-shortest" red)
            ;; a 400px opaque lime square at (340,760) on a TRANSPARENT canvas
            _    (sh "ffmpeg" "-y" "-f" "lavfi" "-i" "color=c=lime:s=400x400,format=rgba"
                     "-f" "lavfi" "-i" "color=c=black@0:s=1080x1920,format=rgba"
                     "-filter_complex" "[1][0]overlay=340:760:format=auto,format=rgba" "-frames:v" "1" sq)
            _    (sh "ffmpeg" "-y" "-f" "lavfi" "-i" "sine=f=1000:d=3" tone)
            k    (document/document-kdenlive (str dir "/layers.hkd.edn"))
            call (fn [route params]
                   (let [{:keys [ok error] :as res} (client/-call k route params)]
                     (is (nil? error) (pr-str route params res))
                     ok))]
        (call :project/profile {:width 1080 :height 1920 :fpsNum 30 :fpsDen 1})
        (let [{[r s t] :ids} (call :media/import {:paths [red sq tone]})
              {title :id} (call :media/create-title {:text "Any video." :duration 90 :size 120 :y 300})
              {v1 :id} (call :timeline/add-track {:name "V1" :isAudio false})
              {v2 :id} (call :timeline/add-track {:name "V2" :isAudio false})
              {v3 :id} (call :timeline/add-track {:name "V3" :isAudio false})
              {a1 :id} (call :timeline/add-track {:name "A1" :isAudio true})]
          (testing "a 25 fps probe of a 3 s WAV lands as 90 frames at 30 fps"
            (is (= 90 (:length (first (filter #(= t (:id %)) (:clips (call :media/list {}))))))))
          (call :timeline/insert-clip {:binId r :trackId v1 :position 0})
          (let [{c :id} (call :timeline/insert-clip {:binId s :trackId v2 :position 0 :in 0 :out 59})]
            (call :clip/append-effect {:id c :clipId c :effectId "fade_from_black" :params {:duration 30 :alpha true}}))
          (call :timeline/insert-clip {:binId title :trackId v3 :position 0})
          (let [{c :id} (call :timeline/insert-clip {:binId t :trackId a1 :position 0})]
            (call :clip/audio-fade {:id c :fadeIn 0 :fadeOut 30})))
        (let [res (call :render/start {:outputFile out})]
          (is (= 90 (:frames res)))
          (testing "an .mp4 is H.264 in yuv420p at the profile's size"
            (is (= "h264\n1080\n1920\nyuv420p" (probe-stream out "codec_name,width,height,pix_fmt")))
            (is (= 90 (frame-count out))))
          (testing "the transparent overlay sits ON the red track, it does not replace it"
            (is (= :green (colour (rgb-at out 1.5 540 960))) (pr-str (rgb-at out 1.5 540 960)))
            (is (= :red (colour (rgb-at out 1.5 100 1500))) (pr-str (rgb-at out 1.5 100 1500))))
          (testing "the alpha fade is half way at frame 15: red and lime mixed"
            (let [[rr g b] (rgb-at out 0.5 540 960)]
              (is (and (< 60 rr 200) (< 60 g 200) (< b 40)) (pr-str [rr g b]))))
          (testing "the title is drawn on the top track"
            (is (< 230 (brightest-in-band out 1.0 300 150)))
            (is (> 60 (brightest-in-band out 1.0 1300 150)) "and nowhere else"))
          (testing "both tracks' audio is heard, and the fade-out silences the tone"
            (is (< -30 (tone-level out 440 0 1)))
            (is (< -30 (tone-level out 1000 0 1)))
            (is (> -38 (tone-level out 1000 2.7 0.3)) "the last 0.3 s of a 1 s fade-out")))
        (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))))
