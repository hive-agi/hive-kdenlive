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

(deftest consumer-options-test
  (testing "a map becomes key=value args after the consumer, sorted, before :extra"
    (is (= ["melt" "in.mlt" "-consumer" "avformat:out.mp4" "b=2M" "crf=23" "vcodec=libx264" "-verbose"]
           (render/melt-argv "melt" "in.mlt" "out.mp4"
                             :consumer {:vcodec "libx264" "crf" 23 :b "2M"} :extra ["-verbose"]))))
  (testing "a Kdenlive preset string means the same map"
    (is (= {:ok {"vcodec" "libx264" "crf" "23" "movflags" "+faststart"}}
           (render/consumer-map " vcodec=libx264  crf=23 movflags=+faststart "))))
  (testing "a value may itself contain ="
    (is (= {:ok ["metadata=title=a"]} (render/consumer-args "metadata=title=a"))))
  (testing "nil is no options"
    (is (= {:ok []} (render/consumer-args nil))))
  (testing "what melt could not read as a property is refused by name"
    (is (= :render/bad-consumer-option (:error (render/consumer-map "vcodec"))))
    (is (= :render/bad-consumer-option (:error (render/consumer-map {"bad key" "x"}))))
    (is (= :render/bad-consumer-option (:error (render/consumer-map {:crf [1 2]}))))
    (is (= :render/bad-consumer-option (:error (render/consumer-map 42))))))

(deftest defaults-by-extension-test
  (is (= "libx264" (get (render/encoding-for "a.MP4") "vcodec")))
  (is (= {} (render/encoding-for "a.mkv"))))

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

(deftest facade-consumer-test
  (testing "a bad :consumer is refused before the renderer is called"
    (let [calls (atom [])]
      (binding [render/*renderer* (->StubRenderer calls {:ok {}})]
        (is (= :render/bad-consumer-option
               (:error (render/render! "<mlt/>" "o.mp4" :consumer "nonsense")))))
      (is (= [] @calls))))
  (testing "a good one reaches the renderer as a string map"
    (let [calls (atom [])]
      (binding [render/*renderer* (->StubRenderer calls {:ok {}})]
        (render/render! "<mlt/>" "o.mp4" :consumer "crf=30"))
      (is (= {"crf" "30"} (get-in (first @calls) [:opts :consumer]))))))

(deftest melt-renderer-missing-bin-test
  (is (= :render/melt-not-found
         (:error (render/-render (render/->MeltRenderer nil) "in.mlt" "o.mp4" {})))))
