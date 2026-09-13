(ns hive-kdenlive.kdenlive.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.kdenlive.client :as client]
            [hive-kdenlive.kdenlive.routes :as routes]))

(deftest catalog-test
  (testing "every entry is well-formed"
    (doseq [{:keys [id method path]} routes/catalog]
      (is (keyword? id))
      (is (contains? #{:get :post :put :delete} method))
      (is (re-matches #"/[A-Za-z/{}\-]+" path))))
  (is (= (count routes/catalog) (count routes/by-id)) "ids are unique")
  (is (some? (routes/route :timeline/insert-clip)))
  (is (nil? (routes/route :nope))))

(deftest request-test
  (testing "unknown route"
    (is (= :routes/unknown-route (:error (routes/request :nope {})))))
  (testing "missing required params, named in the error"
    (let [{:keys [error missing]} (routes/request :timeline/insert-clip {:trackId 1})]
      (is (= :routes/missing-params error))
      (is (= #{:binId :position} (set missing)))))
  (testing "get routes carry no body"
    (is (= {:ok {:method :get :path "/render/jobs" :body nil}}
           (routes/request :render/jobs nil))))
  (testing "post routes carry the params as body"
    (let [{:keys [ok]} (routes/request :project/open {:path "/p/x.kdenlive"})]
      (is (= :post (:method ok)))
      (is (= "/project/open" (:path ok)))
      (is (= {:path "/p/x.kdenlive"} (:body ok)))))
  (testing "path templates fill from params"
    (let [{:keys [ok]} (routes/request :timeline/track-clips {:id 3})]
      (is (= "/timeline/tracks/3/clips" (:path ok))))))

(deftest json-test
  (is (= "{\"a\":\"x y\"}" (client/->json {:a "x y"})))
  (is (= "{\"a\":\"say \\\"hi\\\"\"}" (client/->json {:a "say \"hi\""})))
  (is (= "{\"n\":1,\"b\":true}" (client/->json {:n 1 :b true}))))

(defrecord StubKdenlive [calls answer]
  client/IKdenlive
  (-call [_ route-id params]
    (swap! calls conj [route-id params])
    answer))

(deftest facade-test
  (let [calls (atom [])]
    (binding [client/*kdenlive* (->StubKdenlive calls {:ok {:status 200}})]
      (is (= {:ok {:status 200}} (client/call :project/save)))
      (is (true? (client/ping))))
    (is (= [[:project/save {}] [:project/info {}]] @calls)))
  (testing "stub errors surface; ping is false"
    (binding [client/*kdenlive* (->StubKdenlive (atom []) {:error :kdenlive/http-error :status 500})]
      (is (false? (client/ping))))))
