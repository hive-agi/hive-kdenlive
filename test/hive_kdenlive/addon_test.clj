(ns hive-kdenlive.addon-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-kdenlive.addon :as k]
            [hive-kdenlive.kdenlive.client :as client]))

(deftest ctor-is-pure-test
  (let [a (k/addon-ctor {})]
    (is (= "hive.kdenlive" (addon/addon-id a)))
    (is (= :native (addon/addon-type a)))
    (is (= #{:tools :health-reporting} (addon/capabilities a)))
    (is (true? (:success? (addon/initialize! a {}))))
    (is (true? (:success? (addon/initialize! a {}))) "initialize! is idempotent")
    (is (nil? (addon/shutdown! a)))))

(deftest tools-shape-test
  (let [tools (addon/tools (k/addon-ctor {}))]
    (is (= 4 (count tools)))
    (doseq [{:keys [name description inputSchema handler]} tools]
      (is (string? name))
      (is (string? description))
      (is (= "object" (:type inputSchema)))
      (is (fn? handler)))
    (is (= #{"render" "kdenlive_call" "routes" "ping"}
           (into #{} (map :name) tools)))))

(defn- tool-handler
  [tool-name]
  (->> k/tool-defs (filter #(= tool-name (:name %))) first :handler))

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
    (testing "underscore route names map to catalog ids"
      (let [seen (atom nil)]
        (binding [client/*kdenlive* (reify client/IKdenlive
                                     (-call [_ r p] (reset! seen [r p]) {:ok {}}))]
          (call-h {"route" "project_open" "params" {:path "/p/x.kdenlive"}})
          (is (= :project/open (first @seen))))))))

(deftest health-test
  (let [{:keys [status details]} (addon/health (k/addon-ctor {}))]
    (is (= :ok status))
    (is (contains? details :melt-on-path?))
    (is (= 12 (:routes details)))))
