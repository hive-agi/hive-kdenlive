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
         '[hive-kdenlive.mlt.timeline :as tl])

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

(dotimes [_ 60] (run-checks))

(if (empty? @failures)
  (println "timeline: all checks passed (60 passes)")
  (do (doseq [[label expected actual] (distinct @failures)]
        (println "FAIL" label "expected" (pr-str expected) "got" (pr-str actual)))
      (throw (ex-info (str (count (distinct @failures)) " timeline check(s) failed") {}))))
