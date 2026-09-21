(ns hive-kdenlive.mlt.transitions-test
  "A transition is a plan of catalog verbs over two overlapping clips."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.mlt.model :as model]
            [hive-kdenlive.mlt.timeline :as tl]
            [hive-kdenlive.mlt.transitions :as sut]))

(def lengths {"/m/a.mkv" 50 "/m/b.mkv" 30})

(def ctx {:probe (fn [p] (if-let [n (get lengths p)] {:length n} {:error :media/file-not-found :path p}))})

(defn- run [state verb params]
  (let [{:keys [ok error] :as res} (tl/apply-verb state verb params ctx)]
    (is (nil? error) (pr-str verb params res))
    [(:state ok) (:result ok)]))

(defn- two-tracks
  "a (50 frames) at 0 on V1, b (30 frames) at B-AT on V2. V2 renders on top."
  [b-at]
  (let [[s {[a b] :ids}] (run (tl/new-project "t") :media/import {:paths ["/m/a.mkv" "/m/b.mkv"]})
        [s {v1 :id}]     (run s :timeline/add-track {:name "V1" :isAudio false})
        [s {v2 :id}]     (run s :timeline/add-track {:name "V2" :isAudio false})
        [s {ca :id}]     (run s :timeline/insert-clip {:binId a :trackId v1 :position 0})
        [s {cb :id}]     (run s :timeline/insert-clip {:binId b :trackId v2 :position b-at})]
    {:state s :ca ca :cb cb :v1 v1 :v2 v2 :a a :b b}))

(defn- clip [state id]
  (first (filter (fn [c] (= id (:id c))) (mapcat :clips (:tracks state)))))

(deftest a-dissolve-fades-the-incoming-clip-in-when-it-is-on-top
  (let [{:keys [state ca cb]} (two-tracks 40)
        {:keys [overlap upper calls]} (:ok (sut/plan state {:kind :dissolve :from ca :to cb}))
        {s :ok} (sut/apply-plan state calls ctx)]
    (is (= [40 50] overlap))
    (is (= cb upper))
    (is (= [[0 0] [9 1]] (mapv (juxt :frame :opacity) (:transform (clip s cb)))))
    (is (= {:in 0 :out 10} (:audio-fade (clip s ca))) "the outgoing sound leaves over the overlap")
    (is (= {:in 10 :out 0} (:audio-fade (clip s cb))))
    (testing "and the document carries it as ordinary qtblend keyframes"
      (is (str/includes? (model/emit (tl/->document s)) "0=0 0 1920 1080 0;9=0 0 1920 1080 1")))))

(deftest the-outgoing-clip-fades-out-when-it-is-the-one-on-top
  (let [[s {[a b] :ids}] (run (tl/new-project "t") :media/import {:paths ["/m/a.mkv" "/m/b.mkv"]})
        [s {v1 :id}]     (run s :timeline/add-track {:name "V1" :isAudio false})
        [s {v2 :id}]     (run s :timeline/add-track {:name "V2" :isAudio false})
        [s {ca :id}]     (run s :timeline/insert-clip {:binId a :trackId v2 :position 0})
        [s {cb :id}]     (run s :timeline/insert-clip {:binId b :trackId v1 :position 40})
        {:keys [upper calls]} (:ok (sut/plan s {:from ca :to cb}))
        {s :ok} (sut/apply-plan s calls ctx)]
    (is (= ca upper))
    (is (= [[40 1] [49 0]] (mapv (juxt :frame :opacity) (:transform (clip s ca))))
        "its LAST ten frames, uncovering the clip under it")
    (is (nil? (:transform (clip s cb))))))

(deftest a-slide-moves-the-picture-and-never-its-opacity
  (let [{:keys [state ca cb]} (two-tracks 40)
        {:keys [calls]} (:ok (sut/plan state {:kind :slide-left :from ca :to cb}))
        {s :ok} (sut/apply-plan state calls ctx)]
    (is (= [[0 1920 0 1] [9 0 0 1]] (mapv (juxt :frame :x :y :opacity) (:transform (clip s cb)))))))

(deftest every-call-is-a-route-the-fork-catalog-names
  (let [{:keys [state ca cb]} (two-tracks 40)
        catalog (set (map :id (deref (requiring-resolve 'hive-kdenlive.kdenlive.routes/catalog))))]
    (doseq [kind sut/kinds]
      (is (every? catalog (map first (:calls (:ok (sut/plan state {:kind kind :from ca :to cb})))))
          (str kind)))))

(deftest what-cannot-be-a-transition-is-refused-by-name
  (let [{:keys [state ca cb v1 b]} (two-tracks 40)]
    (is (= :transition/unknown-kind (:error (sut/plan state {:kind :star-wipe :from ca :to cb}))))
    (is (= :timeline/no-such-clip (:error (sut/plan state {:from ca :to "nope"}))))
    (is (= :transition/incoming-must-start-inside-outgoing
           (:error (sut/plan state {:from cb :to ca}))) "the wrong way round")
    (testing "clips that only touch do not overlap"
      (let [{:keys [state ca cb]} (two-tracks 50)]
        (is (= :transition/incoming-must-start-inside-outgoing
               (:error (sut/plan state {:from ca :to cb}))))))
    (testing "one shared frame is not a transition"
      (let [{:keys [state ca cb]} (two-tracks 49)]
        (is (= :transition/no-overlap (:error (sut/plan state {:from ca :to cb}))))))
    (testing "two clips of one track"
      (let [[s {c2 :id}] (run state :timeline/insert-clip {:binId b :trackId v1 :position 60})]
        (is (= :transition/same-track (:error (sut/plan s {:from ca :to c2}))))))
    (testing "keyframes already on the upper clip are not overwritten"
      (let [[s _] (run state :clip/transform-keyframe
                       {:clipId cb :frame 0 :x 0 :y 0 :width 1920 :height 1080 :opacity 1})]
        (is (= :transition/clip-has-transform (:error (sut/plan s {:from ca :to cb}))))))))
