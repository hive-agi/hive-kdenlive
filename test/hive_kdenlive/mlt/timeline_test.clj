(ns hive-kdenlive.mlt.timeline-test
  "The headless timeline answers the fork's route ids against a value.

   The probe is a fake (a map from path to length). What it cannot vouch for,
   melt's lengths and a real render, is document_integration_test's job."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.mlt.model :as model]
            [hive-kdenlive.mlt.project :as project]
            [hive-kdenlive.mlt.timeline :as tl]
            [hive-kdenlive.mlt.xml :as xml]))

(def lengths {"/m/a.mkv" 50 "/m/b.mkv" 30 "/m/c.mkv" 20})

(def ctx {:probe (fn [p] (if-let [n (get lengths p)] {:length n} {:error :media/file-not-found :path p}))})

(defn- run
  "Apply VERB to STATE; return [state result] and fail the test on an error."
  [state verb params]
  (let [{:keys [ok error] :as res} (tl/apply-verb state verb params ctx)]
    (is (nil? error) (pr-str verb params res))
    [(:state ok) (:result ok)]))

(defn- refuse [state verb params]
  (:error (tl/apply-verb state verb params ctx)))

(defn- positions [state track-id]
  (let [[_ {:keys [clips]}] (run state :timeline/track-clips {:id track-id})]
    (mapv (juxt :position :duration) clips)))

(defn- base
  "A project with a, b, c imported and one video track."
  []
  (let [[s {[a b c] :ids}] (run (tl/new-project "t") :media/import {:paths ["/m/a.mkv" "/m/b.mkv" "/m/c.mkv"]})
        [s {v :id}]        (run s :timeline/add-track {:name "V1" :isAudio false})]
    {:state s :a a :b b :c c :v v}))

(deftest import-probes-every-path-and-is-all-or-nothing
  (let [{:keys [state a b c]} (base)]
    (is (= ["1" "2" "3"] [a b c]))
    (is (= [50 30 20] (mapv #(get-in state [:bin % :length]) [a b c])))
    (is (= "a.mkv" (get-in state [:bin a :name]))))
  (testing "one unreadable path refuses the whole import and adds nothing"
    (let [s (tl/new-project)
          res (tl/apply-verb s :media/import {:paths ["/m/a.mkv" "/m/missing.mkv"]} ctx)]
      (is (= :media/unreadable (:error res)))
      (is (= "/m/missing.mkv" (:path res)))))
  (is (= :media/no-probe (get-in (tl/apply-verb (tl/new-project) :media/import {:paths ["/m/a.mkv"]} {})
                                 [:detail :error]))))

(deftest insert-clip-places-and-refuses-overlap
  (let [{:keys [state a b v]} (base)
        [s {c1 :id}] (run state :timeline/insert-clip {:binId a :trackId v :position 10})
        [s _]        (run s :timeline/insert-clip {:binId b :trackId v :position 60})]
    (is (string? c1))
    (is (= [[10 50] [60 30]] (positions s v)))
    (is (= 90 (tl/duration s)))
    (testing "a clip that would share a frame is refused, naming what it hits"
      (let [res (tl/apply-verb s :timeline/insert-clip {:binId b :trackId v :position 55} ctx)]
        (is (= :timeline/overlap (:error res)))
        ;; [55, 85) reaches back into the first clip and forward into the second.
        (is (= 2 (count (:clips res))))
        (is (some #{c1} (:clips res)))))
    (is (= :timeline/no-such-bin-clip (refuse s :timeline/insert-clip {:binId "99" :trackId v :position 200})))
    (is (= :timeline/no-such-track (refuse s :timeline/insert-clip {:binId a :trackId "99" :position 200})))
    (is (= :timeline/bad-range (refuse s :timeline/insert-clip {:binId a :trackId v :position 200 :in 10 :out 60})))
    (testing "a sub-range of the clip"
      (let [[s _] (run s :timeline/insert-clip {:binId a :trackId v :position 100 :in 5 :out 14})]
        (is (= [[10 50] [60 30] [100 10]] (positions s v)))))))

(deftest batch-is-back-to-back-and-atomic
  (let [{:keys [state a b c v]} (base)
        [s {:keys [ids]}] (run state :timeline/insert-clips-batch {:binIds [a b c] :trackId v :startPosition 5})]
    (is (= 3 (count ids)))
    (is (= [[5 50] [55 30] [85 20]] (positions s v)))
    (testing "a batch that would collide places nothing"
      (let [before s
            res    (tl/apply-verb s :timeline/insert-clips-batch {:binIds [c a] :trackId v :startPosition 0} ctx)]
        (is (= :timeline/overlap (:error res)))
        (is (= (positions before v) (positions s v)))))))

(deftest insert-space-shifts-later-clips-and-refuses-to-cut
  (let [{:keys [state a b v]} (base)
        [s _] (run state :timeline/insert-clip {:binId a :trackId v :position 0})
        [s _] (run s :timeline/insert-clip {:binId b :trackId v :position 50})
        [s2 _] (run s :timeline/insert-space {:trackId v :position 50 :duration 25})]
    (is (= [[0 50] [75 30]] (positions s2 v)))
    (is (= :timeline/space-splits-clip (refuse s :timeline/insert-space {:trackId v :position 20 :duration 5})))
    (is (= :timeline/bad-duration (refuse s :timeline/insert-space {:trackId v :position 50 :duration 0})))
    (testing "allTracks shifts every track"
      (let [[s {v2 :id}] (run s :timeline/add-track {:name "V2"})
            [s _]        (run s :timeline/insert-clip {:binId b :trackId v2 :position 60})
            [s _]        (run s :timeline/insert-space {:position 50 :duration 10 :allTracks true})]
        (is (= [[0 50] [60 30]] (positions s v)))
        (is (= [[70 30]] (positions s v2)))))))

(deftest zone-extract-lifts-or-ripples-and-refuses-to-cut
  (let [{:keys [state a b c v]} (base)
        [s _] (run state :timeline/insert-clips-batch {:binIds [a b c] :trackId v :startPosition 0})]
    (testing "ripple: the zone closes"
      (let [[s _] (run s :timeline/zone-extract {:inFrame 50 :outFrame 79})]
        (is (= [[0 50] [50 20]] (positions s v)))))
    (testing "lift: the gap stays"
      (let [[s _] (run s :timeline/zone-extract {:inFrame 50 :outFrame 79 :liftOnly true})]
        (is (= [[0 50] [80 20]] (positions s v)))))
    (is (= :timeline/zone-splits-clip (refuse s :timeline/zone-extract {:inFrame 40 :outFrame 60})))
    (is (= :timeline/bad-zone (refuse s :timeline/zone-extract {:inFrame 60 :outFrame 40})))))

(deftest tracks-delete-and-info
  (let [{:keys [state a v]} (base)
        [s _] (run state :timeline/insert-clip {:binId a :trackId v :position 0})
        [_ info] (run s :project/info {})
        [_ {ts :tracks}] (run s :timeline/tracks {})
        [s _] (run s :timeline/delete-track {:id v})]
    (is (= {:name "t" :width 1920 :height 1080 :fps 25 :duration 50} info))
    (is (= [{:id v :name "V1" :audio false :clips 1}] ts))
    (is (= [] (:tracks s)))
    (is (= :timeline/no-such-track (refuse s :timeline/delete-track {:id v})))))

(deftest routes-with-no-headless-meaning-are-refused-by-name
  (let [res (tl/apply-verb (tl/new-project) :playback/play {} ctx)]
    (is (= :document/unsupported-route (:error res)))
    (is (some #{"timeline/insert-clip"} (:supported res)))))

(deftest every-verb-is-a-route-the-fork-catalog-names
  (let [catalog (set (map :id (deref (requiring-resolve 'hive-kdenlive.kdenlive.routes/catalog))))]
    (is (every? catalog (keys tl/verbs))
        "a headless verb must answer a route id the HTTP transport also knows")))

(deftest the-document-is-an-mlt-timeline-with-a-background-and-blanks
  (let [{:keys [state a b v]} (base)
        [s {v2 :id}] (run state :timeline/add-track {:name "A1" :isAudio true})
        [s _] (run s :timeline/insert-clip {:binId a :trackId v :position 10})
        [s _] (run s :timeline/insert-clip {:binId b :trackId v2 :position 0 :in 0 :out 9})
        text  (model/emit (tl/->document s))
        {:keys [ok error]} (xml/parse text)
        lists (into {} (map (fn [p] [(xml/attr p "id") p])) (xml/children ok "playlist"))]
    (is (nil? error))
    (testing "the black background covers the whole duration, as a bare colour"
      (let [black (first (filter #(= "black_track" (xml/attr % "id")) (xml/children ok "producer")))]
        (is (str/includes? (xml/emit black) "<property name=\"resource\">black</property>"))
        (is (str/includes? (xml/emit black) "<property name=\"mlt_service\">color</property>"))
        (is (= "59" (xml/attr (first (xml/children (get lists "background") "entry")) "out")))))
    (testing "a gap before a clip is a blank of the right length"
      (let [items (:content (get lists (str "track" v)))]
        (is (= [["blank" "10"] ["entry" "bin1"]]
               (mapv (fn [n] [(:tag n) (or (xml/attr n "length") (xml/attr n "producer"))]) items)))))
    (testing "tracks: background first, then the timeline's, audio tracks hide video"
      (let [tractor (first (xml/children ok "tractor"))
            ts      (xml/children (first (xml/children tractor "multitrack")) "track")]
        (is (= ["background" (str "track" v) (str "track" v2)] (mapv #(xml/attr % "producer") ts)))
        (is (= "video" (xml/attr (last ts) "hide")))))
    (testing "the project reader sees the same bin"
      (is (= #{"/m/a.mkv" "/m/b.mkv" "/m/c.mkv" "black"}
             (set (map :resource (:bin (:ok (project/summarize text))))))))))

(defn- doc-node
  "The parsed MLT document for STATE."
  [state]
  (:ok (xml/parse (model/emit (tl/->document state)))))

(defn- props
  "Property name -> text of a filter/transition node."
  [node]
  (into {} (map (fn [p] [(xml/attr p "name") (xml/text p)])) (xml/children node "property")))

(defn- entry-filters
  "[filter props with in/out] nested in the entries of playlist `track<id>`."
  [state track-id]
  (let [pl (first (filter #(= (str "track" track-id) (xml/attr % "id")) (xml/children (doc-node state) "playlist")))]
    (vec (mapcat (fn [e] (map (fn [f] (assoc (props f) :in (xml/attr f "in") :out (xml/attr f "out")))
                              (xml/children e "filter")))
                 (xml/children pl "entry")))))

(deftest profile-sets-size-rate-and-a-matching-aspect
  (let [[s _] (run (tl/new-project "p") :project/profile {:width 1080 :height 1920 :fpsNum 30 :fpsDen 1})
        [_ info] (run s :project/info {})
        profile (first (xml/children (doc-node s) "profile"))]
    (is (= {:width 1080 :height 1920 :fps 30} (select-keys info [:width :height :fps])))
    (is (= ["9" "16" "30" "1"] (mapv #(xml/attr profile %) ["display_aspect_num" "display_aspect_den"
                                                           "frame_rate_num" "frame_rate_den"])))
    (let [[s _] (run s :project/profile {:width 1920 :height 1080 :fpsNum 30000 :fpsDen 1001})]
      (is (= [30000 1001] (get-in s [:profile :fps]))))
    (is (= :project/bad-profile (refuse s :project/profile {:width 0 :height 1920 :fpsNum 30 :fpsDen 1})))))

(deftest import-counts-lengths-at-the-project-rate
  (testing "a probe that reports its own rate is converted, rounding down"
    (let [ctx {:probe (fn [_] {:length 75 :fps [25 1]})}
          s   (:state (:ok (tl/apply-verb (tl/new-project) :project/profile {:width 1080 :height 1920 :fpsNum 30 :fpsDen 1} {})))
          {:keys [ok]} (tl/apply-verb s :media/import {:paths ["/m/tone.wav"]} ctx)]
      (is (= 90 (get-in ok [:state :bin "1" :length])))))
  (is (= 90 (tl/at-project-rate 75 [25 1] 30)))
  (is (= 29 (tl/at-project-rate 30 [30 1] [30000 1001])) "never a frame the source lacks")
  (is (= 50 (tl/at-project-rate 50 nil 30)) "no rate: already the project's"))

(deftest titles-are-kdenlive-title-producers
  (let [[s {t :id}] (run (tl/new-project) :media/create-title {:text "Any video." :duration 45 :size 120 :weight 700})
        [s {x :id}] (run s :media/create-title {:xml "<kdenlivetitle/>" :duration 10 :name "raw"})
        prods (into {} (map (fn [p] [(xml/attr p "id") (props p)])) (xml/children (doc-node s) "producer"))
        title (get prods (str "bin" t))]
    (is (= "kdenlivetitle" (get title "mlt_service")))
    (is (= "45" (get title "length")))
    (is (str/includes? (get title "xmldata") "Any video."))
    (is (str/includes? (get title "xmldata") "font-weight=\"75\"") "CSS 700 is stored as Qt5 bold")
    (is (= "<kdenlivetitle/>" (get-in prods [(str "bin" x) "xmldata"])) "xml is taken as given")
    (is (= :title/bad-duration (refuse s :media/create-title {:text "x" :duration 0})))
    (is (= :title/xml-or-text-required (refuse s :media/create-title {:duration 10})))))

(deftest effects-are-filters-in-the-entry-with-clip-relative-windows
  (let [{:keys [state a v]} (base)
        ;; a is 50 frames; place frames 10..39 so source frames differ from clip frames
        [s {c :id}] (run state :timeline/insert-clip {:binId a :trackId v :position 0 :in 10 :out 39})
        [s _] (run s :clip/append-effect {:id c :clipId c :effectId "fade_from_black" :params {:duration 5}})
        [s _] (run s :clip/append-effect {:id c :clipId c :effectId "fade_to_black" :params {"duration" "10" "alpha" "true"}})
        [s _] (run s :clip/append-effect {:id c :clipId c :effectId "sepia" :params {:u 75}})
        fs    (entry-filters s v)]
    (testing "in/out are source frames: the clip's in plus the window"
      (is (= [["brightness" "10" "14"] ["brightness" "30" "39"] ["sepia" "10" "39"]]
             (mapv (juxt #(get % "mlt_service") :in :out) fs))))
    (testing "Kdenlive's fades: level ramps from black, alpha ramps to transparent"
      (is (= {"level" "0=0;-1=1" "alpha" "1"} (select-keys (first fs) ["level" "alpha"])))
      (is (= {"level" "1" "alpha" "0=1;-1=0"} (select-keys (second fs) ["level" "alpha"]))))
    (is (= "75" (get (nth fs 2) "u")) "a raw service keeps its params")
    (is (= :effect/bad-duration (refuse s :clip/append-effect {:id c :effectId "fade_from_black" :params {:duration 31}})))
    (is (= :timeline/no-such-clip (refuse s :clip/append-effect {:id "99" :effectId "sepia"})))
    (is (= :effect/id-required (refuse s :clip/append-effect {:id c})))))

(deftest transform-opacity-volume-and-audio-fades
  (let [{:keys [state a v]} (base)
        [s {c :id}] (run state :timeline/insert-clip {:binId a :trackId v :position 0})
        [s _] (run s :clip/transform-keyframe {:id c :frame 49 :x 540 :y 0 :width 1920 :height 1080})
        [s _] (run s :clip/transform-keyframe {:id c :frame 0 :x 0 :y 0 :width 1920 :height 1080 :opacity 0.5})
        [s _] (run s :clip/transform-keyframe {:id c :frame 0 :x 10 :y 0 :width 1920 :height 1080})
        [s _] (run s :clip/volume {:id c :dB -6})
        [s _] (run s :clip/audio-fade {:id c :fadeIn 5 :fadeOut 10})
        fs    (entry-filters s v)
        by    (group-by #(get % "mlt_service") fs)]
    (is (= "0=10 0 1920 1080 1;49=540 0 1920 1080 1" (get (first (get by "qtblend")) "rect"))
        "sorted by frame; a second keyframe at a frame replaces the first")
    (is (= [["0" "1" "0" "4"] ["1" "0" "40" "49"]]
           (mapv (juxt #(get % "gain") #(get % "end") :in :out) (filter #(get % "gain") (get by "volume")))))
    (is (= "-6" (get (first (filter #(get % "level") (get by "volume"))) "level")))
    (testing "opacity alone is a whole-frame transform"
      (let [[s2 {c2 :id}] (run state :timeline/insert-clip {:binId a :trackId v :position 0})
            [s2 _] (run s2 :clip/opacity {:id c2 :opacity 0.25})]
        (is (= "0 0 1920 1080 0.25" (get (first (entry-filters s2 v)) "rect")))))
    (is (= :clip/bad-keyframe-frame (refuse s :clip/transform-keyframe {:id c :frame 50 :x 0 :y 0 :width 1 :height 1})))
    (is (= :clip/bad-opacity (refuse s :clip/opacity {:id c :opacity 2})))
    (is (= :clip/bad-fade (refuse s :clip/audio-fade {:id c :fadeIn 30 :fadeOut 30})))))

(deftest every-track-is-composited-and-mixed
  (let [{:keys [state a b v]} (base)
        [s {v2 :id}] (run state :timeline/add-track {:name "V2"})
        [s {a1 :id}] (run s :timeline/add-track {:name "A1" :isAudio true})
        tractor (first (xml/children (doc-node s) "tractor"))
        ts      (mapv props (xml/children tractor "transition"))]
    (testing "a qtblend per VIDEO track, onto the background: without it the top track replaces what is under it"
      (is (= [["0" "1"] ["0" "2"]]
             (mapv (juxt #(get % "a_track") #(get % "b_track")) (filter #(= "qtblend" (get % "mlt_service")) ts)))))
    (testing "a mix per track: without it only the top track is heard"
      (is (= [["0" "1"] ["0" "2"] ["0" "3"]]
             (mapv (juxt #(get % "a_track") #(get % "b_track")) (filter #(= "mix" (get % "mlt_service")) ts))))
      (is (every? #(= "1" (get % "sum")) (filter #(= "mix" (get % "mlt_service")) ts))))))
