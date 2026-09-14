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
