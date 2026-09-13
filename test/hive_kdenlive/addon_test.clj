(ns hive-kdenlive.addon-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-kdenlive.addon :as k]
            [hive-kdenlive.kdenlive.client :as client]
            [hive-kdenlive.mlt.model :as model]))

(deftest ctor-is-pure-test
  (let [a (k/addon-ctor {})]
    (is (= "hive.kdenlive" (addon/addon-id a)))
    (is (= :native (addon/addon-type a)))
    (is (= #{:tools :health-reporting} (addon/capabilities a)))
    (is (true? (:success? (addon/initialize! a {}))))
    (is (true? (:success? (addon/initialize! a {}))) "initialize! is idempotent")
    (is (nil? (addon/shutdown! a)))))

(defn- tool-handler
  [tool-name]
  (->> k/tool-defs (filter #(= tool-name (:name %))) first :handler))

(deftest tools-shape-test
  (let [tools (addon/tools (k/addon-ctor {}))]
    (is (= 5 (count tools)))
    (doseq [{:keys [name description inputSchema handler]} tools]
      (is (string? name))
      (is (string? description))
      (is (= "object" (:type inputSchema)))
      (is (fn? handler)))
    (is (= #{"render" "kdenlive_call" "routes" "ping" "inspect_project"}
           (into #{} (map :name) tools)))))

(deftest inspect-project-handler-test
  (let [inspect-h (tool-handler "inspect_project")]
    (testing "bad params and missing files are data errors"
      (is (= :kdenlive/bad-params (:error (inspect-h {}))))
      (is (= :kdenlive/file-not-found (:error (inspect-h {"path" "/nope/x.kdenlive"})))))
    (testing "a real project file summarizes"
      (let [f (java.io.File/createTempFile "hive-kdenlive-test-" ".kdenlive")]
        (try
          (spit f (model/emit (model/document
                               (model/profile :fps 25)
                               (model/producer "color:red" :id "p0")
                               (model/playlist "pl0" [(model/entry "p0" :in "0" :out "24")])
                               (model/tractor "t0" [(model/track "pl0")]))))
          (let [{:keys [ok error]} (inspect-h {"path" (.getAbsolutePath f)})]
            (is (nil? error))
            (is (= [25 1] (get-in ok [:profile :fps])))
            (is (= [{:id "p0" :resource "color:red"}] (:bin ok)))
            (is (= 25 (:duration-frames ok))))
          (finally (.delete f)))))))

(deftest render-handler-validation-test
  (let [render-h (tool-handler "render")]
    (is (= :kdenlive/bad-params (:error (render-h {"out" "o.mp4"}))))
    (is (= :kdenlive/bad-params (:error (render-h {"mlt_xml" "<mlt/>"}))))))

(defrecord StubKdenlive [answer]
  client/IKdenlive
  (-call [_ _route-id _params] answer))

(deftest kdenlive-call-handler-test
  (let [call-h (tool-handler "kdenlive_call")]
    (testing "unknown route surfaces as data"
      (binding [client/*kdenlive* (->StubKdenlive {:error :routes/unknown-route})]
        (is (= :routes/unknown-route
               (:error (call-h {"route" "nope"}))))))
    (testing "route strings normalize to catalog ids"
      (let [seen (atom nil)]
        (binding [client/*kdenlive* (reify client/IKdenlive
                                     (-call [_ r p] (reset! seen [r p]) {:ok {}}))]
          (doseq [[input expected] {"project_open"        :project/open
                                    "project/open"        :project/open
                                    "timeline_insert-clip" :timeline/insert-clip
                                    "timeline/insert-clip" :timeline/insert-clip
                                    "render_jobs"         :render/jobs}]
            (reset! seen nil)
            (call-h {"route" input "params" {}})
            (is (= expected (first @seen)) (str input " -> " expected))))))))

(deftest health-test
  (let [{:keys [status details]} (addon/health (k/addon-ctor {}))]
    (is (= :ok status))
    (is (contains? details :melt-on-path?))
    (is (= 27 (:routes details)))))
