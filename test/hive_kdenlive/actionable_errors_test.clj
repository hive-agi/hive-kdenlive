(ns hive-kdenlive.actionable-errors-test
  "A refusal from the route catalog has to tell the reader what to do next.

   The portable namespaces answer error VALUES, because `routes` runs on cljw
   and cljrs where hive-help does not exist. `hive-kdenlive.addon/actionable`
   renders those values at the boundary, using the fleet's shared error
   vocabulary (hive-help) rather than a message invented here.

   What is pinned below is that the message names the mistake, the alternative
   and, where there is one, the nearest thing the caller probably meant. The
   exact wording is hive-help's and is not pinned."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.addon :as addon]
            [hive-kdenlive.kdenlive.client :as client]
            [hive-kdenlive.kdenlive.routes :as routes]))

(defn- call [in]
  (let [h (:handler (first (filter #(= "kdenlive_call" (:name %)) addon/tool-defs)))]
    (h in)))

(defn- refusing-client
  "A real HttpKdenlive pointed at a port nothing listens on.

   Route and param validation happens INSIDE the transport (`send!` calls
   `routes/request` before it opens a connection), so every refusal below is
   produced without a byte on the wire. A hand-written stub would have to
   re-implement that validation, which is the thing under test."
  []
  (client/http-kdenlive "http://127.0.0.1:1/api/v1"))

(deftest a-mistyped-route-suggests-the-real-one-test
  (binding [client/*kdenlive* (refusing-client)]
    (let [{:keys [error message]} (call {"route" "timeline/insert-clips" "params" {}})]
      (is (= :routes/unknown-route error))
      (is (string? message))
      (testing "it names what was wrong"
        (is (str/includes? message "insert-clips")))
      (testing "it offers the nearest real route"
        (is (str/includes? message "timeline/insert-clip")
            message))
      (testing "it lists the catalog rather than leaving the reader to find it"
        (is (str/includes? message "render/start") message)))))

(deftest an-undeclared-param-names-what-the-route-does-declare-test
  (binding [client/*kdenlive* (refusing-client)]
    ;; Everything REQUIRED is present, so the refusal is about the extra key
    ;; and not about a missing one: missing-params is checked first.
    (let [{:keys [error message undeclared]}
          (call {"route"  "timeline/add-track"
                 "params" {"name" "V1" "isAudio" false "isAudioo" true}})]
      (is (= :routes/undeclared-params error))
      (is (= [:isAudioo] undeclared))
      (testing "it names the offending param and the declared ones"
        (is (str/includes? message "isAudioo") message)
        (is (str/includes? message "isAudio") message))
      (testing "it suggests the near miss"
        (is (str/includes? message "did you mean") message))
      (testing "the route keeps its namespace"
        (is (str/includes? message "timeline/add-track") message)))))

(deftest a-missing-required-param-says-which-and-in-whose-spelling-test
  (binding [client/*kdenlive* (refusing-client)]
    (let [{:keys [error message missing]}
          (call {"route" "timeline/insert-clip" "params" {"binId" "3"}})]
      (is (= :routes/missing-params error))
      (is (= #{:trackId :position} (set missing)))
      (is (str/includes? message "trackId") message)
      (testing "camelCase is the fork's spelling and the reader is told so"
        (is (str/includes? message "binId") message)))))

(deftest a-good-call-is-not-touched-test
  (binding [client/*kdenlive* (reify client/IKdenlive
                                (-call [_ _ _] {:ok {:id 7}}))]
    (is (= {:ok {:id 7}} (call {"route" "timeline/tracks" "params" {}}))
        "actionable must not add a :message to a success")))

(deftest every-route-in-the-catalog-is-offered-by-name-test
  (binding [client/*kdenlive* (refusing-client)]
    (let [{:keys [message]} (call {"route" "definitely/not-a-route" "params" {}})]
      (doseq [id (map :id routes/catalog)]
        (is (str/includes? message (str (namespace id) "/" (name id)))
            (str id " is in the catalog but not offered in the message"))))))
