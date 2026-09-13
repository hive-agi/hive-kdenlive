(ns hive-kdenlive.render-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.mlt.model :as model]
            [hive-kdenlive.render :as render]))

(deftest melt-argv-test
  (is (= ["melt" "in.mlt" "-consumer" "avformat:out.mp4"]
         (render/melt-argv "melt" "in.mlt" "out.mp4")))
  (is (= ["melt" "in.mlt" "profile=hdv_720_25p" "-consumer" "avformat:out.mp4" "-verbose"]
         (render/melt-argv "melt" "in.mlt" "out.mp4"
                           :profile "hdv_720_25p" :extra ["-verbose"]))))

(deftest which-test
  (is (some? (render/which "sh")))
  (is (nil? (render/which "definitely-not-a-binary-hive-kdenlive"))))

(defrecord StubRenderer [calls answer]
  render/IRender
  (-render [_ mlt-path out-path opts]
    (swap! calls conj {:mlt mlt-path :out out-path :opts opts})
    answer))

(deftest facade-test
  (testing "render! dispatches through *renderer* and reports :ok"
    (let [calls (atom [])
          stub  (->StubRenderer calls {:ok {:out "o.mp4"}})]
      (binding [render/*renderer* stub]
        (is (= {:ok {:out "o.mp4"}}
               (render/render! "<mlt/>" "o.mp4"))))
      (is (= 1 (count @calls)))
      (is (str/ends-with? (:mlt (first @calls)) ".mlt") "document goes via a temp file")))
  (testing "errors propagate as data"
    (let [stub (->StubRenderer (atom []) {:error :render/melt-failed :exit 1})]
      (binding [render/*renderer* stub]
        (is (= :render/melt-failed (:error (render/render! "<mlt/>" "o.mp4")))))))
  (testing "render-doc! emits the model first"
    (let [calls (atom [])]
      (binding [render/*renderer* (->StubRenderer calls {:ok {}})]
        (render/render-doc! (model/document (model/profile)) "o.mp4"))
      (is (= 1 (count @calls))))))

(deftest melt-renderer-missing-bin-test
  (is (= :render/melt-not-found
         (:error (render/-render (render/->MeltRenderer nil) "in.mlt" "o.mp4" {})))))
