(ns hive-kdenlive.mlt.model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.mlt.model :as model]
            [hive-kdenlive.mlt.xml :as xml]))

(deftest producer-test
  (let [p (model/producer "clip.mp4" :id "p0" :in "00:00:01.000" :out "00:00:05.000"
                          :properties {:mute_on_pause 1})]
    (is (= "p0" (xml/attr p "id")))
    (is (= "clip.mp4" (xml/text (first (xml/children p "property")))))
    (is (= 2 (count (xml/children p "property"))) "resource + extra properties")
    (is (nil? (xml/attr p "length")) "unused opts are dropped")))

(deftest filter-test
  (let [f (model/filter "obscure" {:rect "100 100 200 200" :version 1} :id "f0")]
    (is (= "obscure" (xml/text (first (xml/children f "property")))))
    (is (some #(= "rect" (xml/attr % "name")) (xml/children f "property")))))

(deftest playlist-test
  (let [pl (model/playlist "pl0" [(model/entry "p0" :in "00:00:00.000" :out "00:00:05.000")
                                  (model/blank 25)
                                  (model/entry "p1")])]
    (is (= 3 (count (:content pl))))
    (is (= "25" (xml/attr (nth (:content pl) 1) "length")))))

(deftest tractor-test
  (let [t (model/tractor "tractor0"
            [(model/track "background")
             (model/track "pl0" :hide :audio)]
            :transitions [(model/transition "frei0r.cairoblend" 0 1 {})]
            :filters     [(model/filter "volume" {:level -6})])]
    (is (= 2 (count (xml/children (first (xml/children t "multitrack")) "track"))))
    (is (= "audio" (xml/attr (second (xml/children (first (xml/children t "multitrack")) "track"))
                             "hide")))
    (is (= 1 (count (xml/children t "transition"))))
    (is (= 1 (count (xml/children t "filter"))))))

(deftest profile-test
  (testing "integer fps stays 1-denominator"
    (is (= ["25" "1"] [(xml/attr (model/profile :fps 25) "frame_rate_num")
                       (xml/attr (model/profile :fps 25) "frame_rate_den")])))
  (testing "29.97 maps to 30000/1001"
    (is (= ["30000" "1001"] [(xml/attr (model/profile :fps [30000 1001]) "frame_rate_num")
                             (xml/attr (model/profile :fps [30000 1001]) "frame_rate_den")])))
  (testing "23.976 as a float rounds to 24000/1001"
    (let [p (model/profile :fps 23.976)]
      (is (= ["24000" "1001"] [(xml/attr p "frame_rate_num")
                               (xml/attr p "frame_rate_den")])))))

(deftest document-round-trip-test
  (let [doc (model/document
             (model/profile :width 1920 :height 1080 :fps 25)
             (model/producer "/v/clip.mp4" :id "p0" :out "00:00:05.000")
             (model/playlist "pl0" [(model/entry "p0" :out "00:00:05.000")])
             (model/tractor "tractor0" [(model/track "pl0")]))
        out (model/emit doc)
        {:keys [ok error]} (model/parse out)]
    (is (str/starts-with? out "<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
    (is (str/includes? out "<mlt LC_NUMERIC=\"C\""))
    (is (nil? error))
    (is ok "the emitted document parses")
    (is (= "p0" (xml/attr (first (xml/children ok "producer")) "id")))
    (is (= out (model/emit ok)) "round-trip is byte-stable")))
