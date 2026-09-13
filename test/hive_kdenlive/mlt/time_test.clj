(ns hive-kdenlive.mlt.time-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.mlt.time :as t]))

(deftest fps-pair-test
  (is (= [30000 1001] (t/fps-pair [30000 1001])))
  (is (= [25 1] (t/fps-pair 25)))
  (is (= [30000 1001] (t/fps-pair 29.97)))
  (is (= [24000 1001] (t/fps-pair 23.976))))

(deftest clock-parse-test
  (is (= 0 (t/clock->millis "00:00:00.000")))
  (is (= 1000 (t/clock->millis "00:00:01.000")))
  (is (= 61040 (t/clock->millis "00:01:01.040")))
  (is (= 3723456 (t/clock->millis "01:02:03.456")))
  (is (= 61000 (t/clock->millis "01:01")) "MM:SS without hours/millis")
  (is (= 1500 (t/clock->millis "1.5")) "seconds.millis only")
  (is (nil? (t/clock->millis "junk")))
  (is (nil? (t/clock->millis 42))))

(deftest clock-format-test
  (is (= "00:00:00.000" (t/millis->clock 0)))
  (is (= "00:01:01.040" (t/millis->clock 61040)))
  (is (= "01:02:03.456" (t/millis->clock 3723456)))
  (is (= "10:00:00.000" (t/millis->clock 36000000)) "two-digit hours"))

(deftest frames-round-trip-test
  (testing "one second at each rate"
    (is (= 25 (t/clock->frames "00:00:01.000" 25)))
    (is (= 30 (t/clock->frames "00:00:01.000" [30000 1001])) "NTSC: ~30 frames per second")
    (is (= 24 (t/clock->frames "00:00:01.000" [24000 1001]))))
  (is (= 0 (t/clock->frames "00:00:00.000" 25)))
  (is (= 150 (t/clock->frames "150" 25)) "bare integers pass through")
  (is (= "00:00:01.000" (t/frames->clock 25 25)))
  (is (= "00:00:10.000" (t/frames->clock 250 25)))
  (is (= 33 (t/frames->millis 1 [30000 1001])) "NTSC frame ≈ 33.37ms")
  (doseq [n [0 1 25 1499 90000]]
    (is (= n (t/clock->frames (t/frames->clock n 25) 25))
        (str "round-trip at 25fps for frame " n))))

(deftest duration-test
  (is (= 125 (t/duration "00:00:00.000" "00:00:04.960" 25)) "inclusive cut")
  (is (= 1 (t/duration 7 7 25)))
  (is (nil? (t/duration 10 5 25)) "reversed cut is invalid"))
