;; The headless timeline (hive-kdenlive.mlt.timeline) on every host that has to agree:
;;
;;   clojure -M -e nil dev/timeline_portability.cljc     (JVM; or clojure -M dev/timeline_portability.cljc)
;;   cljw -cp src dev/timeline_portability.cljc
;;   cljrs run --src-path src dev/timeline_portability.cljc
;;
;; Expected values are spelled out. The checks run 60 times, because clojurust
;; compiles a fn after 50 calls and its compiled tier has answered differently
;; from its interpreter (identical?/true?/false? on booleans, measured
;; 2026-09-13). A failure THROWS: clojurust resolves no exit primitive.

(require '[clojure.string :as str]
         '[hive-kdenlive.mlt.model :as model]
         '[hive-kdenlive.mlt.timeline :as tl]
         '[hive-kdenlive.mlt.xml :as xml])

(def failures (atom []))

(defn check [label expected actual]
  (when-not (= expected actual)
    (swap! failures conj [label expected actual])))

(def ctx {:probe (fn [p] (get {"/m/a.mkv" {:length 50} "/m/b.mkv" {:length 30}} p {:error :media/file-not-found}))})

(defn step [state verb params]
  (let [{:keys [ok error]} (tl/apply-verb state verb params ctx)]
    (when error (swap! failures conj [(str verb) :no-error error]))
    [(:state ok) (:result ok)]))

(defn run-checks []
  (let [[s {[a b] :ids}] (step (tl/new-project "p") :media/import {:paths ["/m/a.mkv" "/m/b.mkv"]})
        [s {v :id}]      (step s :timeline/add-track {:name "V1" :isAudio false})
        [s {w :id}]      (step s :timeline/add-track {:name "A1" :isAudio true})
        [s _]            (step s :timeline/insert-clip {:binId a :trackId v :position 10})
        [s _]            (step s :timeline/insert-clip {:binId b :trackId v :position 60})
        [s _]            (step s :timeline/insert-space {:trackId v :position 60 :duration 5})
        [_ clips]        (step s :timeline/track-clips {:id v})
        [_ tracks]       (step s :timeline/tracks {})]
    (check "ids" ["1" "2" "3" "4"] [a b v w])
    (check "positions after space" [[10 50] [65 30]] (mapv (juxt :position :duration) (:clips clips)))
    (check "duration" 95 (tl/duration s))
    (check "audio flag survives the hot tier" [false true] (mapv :audio (:tracks tracks)))
    (check "overlap refused" :timeline/overlap
           (:error (tl/apply-verb s :timeline/insert-clip {:binId a :trackId v :position 40} ctx)))
    (check "zone that cuts a clip refused" :timeline/zone-splits-clip
           (:error (tl/apply-verb s :timeline/zone-extract {:inFrame 20 :outFrame 30} ctx)))
    (let [[z _] (step s :timeline/zone-extract {:inFrame 60 :outFrame 64})]
      (check "ripple extract closes the gap" [[10 50] [60 30]]
             (mapv (juxt :position :clip-length)
                   (map (fn [c] (assoc c :clip-length (tl/clip-length c))) (get-in z [:tracks 0 :clips])))))
    (check "unsupported route" :document/unsupported-route (:error (tl/apply-verb s :playback/play {} ctx)))
    (let [text (model/emit (tl/->document s))]
      (check "document has the background track" true (str/includes? text "<track producer=\"background\"/>"))
      (check "document hides video on the audio track" true (str/includes? text (str "<track producer=\"track" w "\" hide=\"video\"/>")))
      (check "gap before the first clip is a blank" true (str/includes? text "<blank length=\"10\"/>")))))

(defn run-layer-checks
  "Profile, titles, clip effects and transforms, and the per-track compositing
   and mix transitions: the verbs a layered promo needs."
  []
  (let [ctx25 {:probe (fn [_] {:length 75 :fps [25 1]})}
        [s _]        (step (tl/new-project "l") :project/profile {:width 1080 :height 1920 :fpsNum 30 :fpsDen 1})
        {:keys [ok]} (tl/apply-verb s :media/import {:paths ["/m/tone.wav"]} ctx25)
        s            (:state ok)
        [s {t :id}]  (step s :media/create-title {:text "Any video." :duration 45 :size 120 :weight 700})
        [s {v :id}]  (step s :timeline/add-track {:name "V1" :isAudio false})
        [s {w :id}]  (step s :timeline/add-track {:name "A1" :isAudio true})
        [s {c :id}]  (step s :timeline/insert-clip {:binId t :trackId v :position 0 :in 5 :out 34})
        [s _]        (step s :timeline/insert-clip {:binId "1" :trackId w :position 0})
        [s _]        (step s :clip/append-effect {:id c :effectId "fade_from_black" :params {"duration" "10" "alpha" "true"}})
        [s _]        (step s :clip/transform-keyframe {:id c :frame 29 :x 540 :y 0 :width 1080 :height 1920 :opacity "0.5"})
        [s _]        (step s :clip/transform-keyframe {:id c :frame 0 :x 0 :y 0 :width 1080 :height 1920})
        text         (model/emit (tl/->document s))]
    (check "wav length at the project rate" 90 (get-in s [:bin "1" :length]))
    (check "profile aspect is the frame's" true (str/includes? text "display_aspect_num=\"9\" display_aspect_den=\"16\""))
    (check "title is a kdenlivetitle producer" true (str/includes? text "<property name=\"mlt_service\">kdenlivetitle</property>"))
    (check "CSS 700 is Qt5 bold" true (str/includes? text "font-weight=\"75\""))
    (check "fade window in source frames" true (str/includes? text "<filter in=\"5\" out=\"14\">"))
    (check "alpha fade ramps alpha" true (str/includes? text "<property name=\"alpha\">0=0;-1=1</property>"))
    (check "keyframes sorted, opacity parsed" true
           (str/includes? text "<property name=\"rect\">0=0 0 1080 1920 1;29=540 0 1080 1920 0.5</property>"))
    (check "video track composited, every track mixed"
           [["qtblend" "0" "1"] ["mix" "0" "1"] ["mix" "0" "2"]]
           (let [tractor (first (xml/children (:ok (model/parse text)) "tractor"))]
             (mapv (fn [tr]
                     (let [ps (into {} (map (fn [p] [(xml/attr p "name") (xml/text p)]) (xml/children tr "property")))]
                       [(get ps "mlt_service") (get ps "a_track") (get ps "b_track")]))
                   (xml/children tractor "transition"))))
    (check "bad opacity refused" :clip/bad-opacity
           (:error (tl/apply-verb s :clip/opacity {:id c :opacity 3} ctx)))))

(dotimes [_ 60] (run-checks))
(dotimes [_ 60] (run-layer-checks))

(if (empty? @failures)
  (println "timeline: all checks passed (60 passes)")
  (do (doseq [[label expected actual] (distinct @failures)]
        (println "FAIL" label "expected" (pr-str expected) "got" (pr-str actual)))
      (throw (ex-info (str (count (distinct @failures)) " timeline check(s) failed") {}))))
