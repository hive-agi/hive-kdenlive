(ns hive-kdenlive.mlt.project-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.mlt.model :as model]
            [hive-kdenlive.mlt.project :as project]))

(def fixture
  "A Kdenlive-shaped document: main_bin, black background, one video track."
  (model/emit
   (model/document
    (model/profile :width 1920 :height 1080 :fps 25)
    (model/producer "/v/a.mp4" :id "p0" :properties {:kdenlive "1"})
    (model/producer "black" :id "black_track" :properties {:mlt_service "color"})
    (model/playlist "main_bin" [(model/entry "p0")])
    (model/playlist "background" [(model/entry "black_track" :in "0" :out "149")])
    (model/playlist "v1" [(model/entry "p0" :in "00:00:00.000" :out "00:00:04.960")
                          (model/blank 25)
                          (model/entry "p0" :in "00:00:01.000" :out "00:00:02.000")])
    (model/tractor "tractor0" [(model/track "background")
                               (model/track "v1" :hide :audio)]))))

(deftest summarize-test
  (let [{:keys [ok error]} (project/summarize fixture)]
    (is (nil? error))
    (testing "profile"
      (is (= {:width 1920 :height 1080 :fps [25 1] :progressive? true}
             (:profile ok))))
    (testing "bin resolves through main_bin"
      (is (= [{:id "p0" :resource "/v/a.mp4"}] (:bin ok))))
    (testing "tracks, in multitrack order, entries as data"
      (is (= 2 (count (:tracks ok))))
      (let [v1 (second (:tracks ok))]
        (is (= "v1" (:id v1)))
        (is (= "audio" (:hide v1)))
        (is (= [{:producer "p0" :in "00:00:00.000" :out "00:00:04.960"}
                {:producer "p0" :in "00:00:01.000" :out "00:00:02.000"}]
               (:entries v1)))
        (is (= [25] (:blanks v1)))))))

(deftest summarize-malformed-test
  (is (= :mlt/xml-malformed (:error (project/summarize "<a></b>")))))

(deftest duration-test
  (let [{:keys [ok]} (project/summarize fixture)]
    ;; v1: 125-frame cut (0->4.960) + 25-frame blank + 26-frame cut
    ;; (1.000->2.000 inclusive) = 176; background: 150 frames of black
    (is (= 176 (project/duration-frames ok))))
  (is (nil? (project/duration-frames {:profile nil :tracks []}))))

(deftest clock-blanks-test
  (testing "Kdenlive writes a blank's length as a clock; it counts in frames"
    (let [doc (str "<mlt><profile width=\"1280\" height=\"720\" frame_rate_num=\"25\" frame_rate_den=\"1\"/>"
                   "<producer id=\"p0\"><property name=\"resource\">/v/a.mp4</property></producer>"
                   "<playlist id=\"pl\"><blank length=\"00:00:00.640\"/>"
                   "<entry producer=\"p0\" in=\"00:00:00.000\" out=\"00:00:00.160\"/></playlist>"
                   "<tractor id=\"t\"><track producer=\"pl\"/></tractor></mlt>")
          {:keys [ok]} (project/summarize doc)]
      (is (= [16] (:blanks (first (:tracks ok)))))
      (is (= 21 (project/duration-frames ok)) "16 blank + 5 frames of clip")))
  (testing "a clock with no fps to resolve it stays as written and counts nothing"
    (let [doc "<mlt><playlist id=\"pl\"><blank length=\"00:00:01.000\"/></playlist><tractor id=\"t\"><track producer=\"pl\"/></tractor></mlt>"]
      (is (= ["00:00:01.000"] (:blanks (first (:tracks (:ok (project/summarize doc))))))))))

(deftest bare-document-bin-test
  (testing "without main_bin, producers ARE the bin"
    (let [doc (model/emit (model/document
                           (model/profile)
                           (model/producer "color:red" :id "p0")))]
      (is (= [{:id "p0" :resource "color:red"}]
             (:bin (:ok (project/summarize doc))))))))

(deftest nested-tractor-test
  (testing "real Kdenlive shape: timeline tractor referencing sub-tractors"
    (let [doc (model/emit
               (model/document
                (model/profile :fps 25)
                (model/producer "black" :id "producer0")
                (model/producer "/v/a.mp4" :id "p0")
                (model/playlist "playlist0" [(model/entry "p0" :in "0" :out "24")])
                (model/playlist "playlist1" [(model/entry "p0" :in "0" :out "24")])
                (model/tractor "tractor0" [(model/track "playlist0" :hide :video)
                                           (model/track "playlist1")])
                (model/tractor "tractor4" [(model/track "producer0")
                                           (model/track "tractor0")])
                ;; Kdenlive's outermost wrapper references the timeline
                (model/tractor "tractor5" [(model/track "tractor4")])))
          {:keys [ok]} (project/summarize doc)
          ts  (:tracks ok)]
      (is (= ["producer0" "tractor0"] (mapv :id ts)) "timeline, not the wrapper")
      (let [sub (second ts)]
        (is (= ["playlist0" "playlist1"] (mapv :id (:tracks sub))))
        (is (= "video" (:hide (first (:tracks sub)))))
        (is (= [{:producer "p0" :in "0" :out "24"}]
               (:entries (second (:tracks sub)))))))))
