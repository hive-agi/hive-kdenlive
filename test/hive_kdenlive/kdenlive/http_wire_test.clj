(ns hive-kdenlive.kdenlive.http-wire-test
  "The HTTP transport against a server that answers the way the scripting fork
   does (kdenlive/src/scripting/httpsession.cpp, routetable.cpp, scriptingserver.cpp):
   it listens where KDENLIVE_SCRIPTING_ADDRESS/PORT say, under /api/v1, refuses
   a request without X-Kdenlive-Secret when a secret is configured, and reads
   route params from a JSON body.

   Before 2026-09-13 the client defaulted to http://localhost:4700, a port the
   fork never opens (its default is 9876), so every call and `ping` missed it.
   `endpoint-follows-the-forks-own-environment-test` holds the default to the
   fork's source; the round-trip tests hold the wire to the fork's contract.

   This server is a double. What it cannot vouch for, and nothing here claims,
   is a live Kdenlive: that is card 20260913200513-6294eb30."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.kdenlive.client :as client])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(defn- with-fork
  "Run F with a base URL for a local server that records requests into an atom
   and answers like the fork: 401 without the right secret, else 200."
  [secret f]
  (let [seen   (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [body     (slurp (.getRequestBody ex) :encoding "UTF-8")
                              given    (.getFirst (.getRequestHeaders ex) "X-Kdenlive-Secret")
                              ok?      (or (nil? secret) (= secret given))
                              answer   (if ok? "{\"id\":7}" "{\"error\":\"Invalid or missing X-Kdenlive-Secret header\"}")
                              bytes    (.getBytes answer StandardCharsets/UTF_8)]
                          (swap! seen conj {:method (.getRequestMethod ex)
                                            :path   (.getPath (.getRequestURI ex))
                                            :body   body
                                            :secret given})
                          (.sendResponseHeaders ex (if ok? 200 401) (count bytes))
                          (with-open [out (.getResponseBody ex)] (.write out bytes))))))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/api/v1") seen)
      (finally (.stop server 0)))))

(deftest endpoint-follows-the-forks-own-environment-test
  (testing "defaults are the fork's defaults, not a port it never opens"
    (is (= {:base-url "http://127.0.0.1:9876/api/v1" :secret nil} (client/endpoint {}))))
  (testing "the fork's variable names configure the client too"
    (is (= {:base-url "http://10.0.0.5:9999/api/v1" :secret "s3"}
           (client/endpoint {"KDENLIVE_SCRIPTING_ADDRESS" "10.0.0.5"
                             "KDENLIVE_SCRIPTING_PORT"    "9999"
                             "KDENLIVE_SCRIPTING_SECRET"  "s3"}))))
  (testing "an empty variable is unset, as the fork's env.value default treats it"
    (is (= {:base-url "http://127.0.0.1:9876/api/v1" :secret nil}
           (client/endpoint {"KDENLIVE_SCRIPTING_PORT" "" "KDENLIVE_SCRIPTING_SECRET" ""})))))

(deftest a-get-route-reaches-its-path-under-api-v1-test
  (with-fork nil
    (fn [base seen]
      (let [res (client/-call (client/http-kdenlive base) :project/info {})]
        (is (= 200 (get-in res [:ok :status])) (pr-str res))
        (is (= [{:method "GET" :path "/api/v1/project" :body "" :secret nil}] @seen))))))

(deftest a-post-route-carries-its-params-as-a-json-body-test
  (with-fork nil
    (fn [base seen]
      (let [res (client/-call (client/http-kdenlive base) :timeline/insert-clip
                              {:binId "3" :trackId 1 :position 250})]
        (is (= 200 (get-in res [:ok :status])) (pr-str res))
        (let [{:keys [method path body]} (first @seen)]
          (is (= ["POST" "/api/v1/timeline/clips"] [method path]))
          (doseq [fragment ["\"binId\":\"3\"" "\"trackId\":1" "\"position\":250"]]
            (is (str/includes? body fragment) body)))))))

(deftest the-secret-is-sent-when-configured-and-its-absence-is-an-error-test
  (with-fork "s3cret"
    (fn [base seen]
      (testing "with the secret, the fork answers"
        (is (= 200 (get-in (client/-call (client/http-kdenlive base "s3cret") :project/info {}) [:ok :status])))
        (is (= "s3cret" (:secret (last @seen)))))
      (testing "without it, a 401 comes back as an error outcome, not a throw"
        (let [res (client/-call (client/http-kdenlive base) :project/info {})]
          (is (= :kdenlive/http-error (:error res)))
          (is (= 401 (:status res))))))))

(deftest a-missing-required-param-never-reaches-the-wire-test
  (with-fork nil
    (fn [base seen]
      (let [res (client/-call (client/http-kdenlive base) :timeline/insert-clip {:binId "3"})]
        (is (some? (:error res)))
        (is (empty? @seen))))))
