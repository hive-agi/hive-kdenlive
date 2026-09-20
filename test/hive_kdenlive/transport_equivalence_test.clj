(ns hive-kdenlive.transport-equivalence-test
  "The two transports must mean the same thing by the same route.

   They did not. `:timeline/insert-clip` carries `:in` and `:out` to trim the
   clip as it is placed. The headless document implemented them; the fork
   cannot (scriptInsertClip binds binId, trackId and position, and
   httpsession.cpp reads only declared params), so over HTTP they were
   encoded, sent and ignored, and the clip landed at full length. Both sides
   answered :ok. hive-creator's :finish recipe therefore produced a trimmed
   timeline headlessly and an untrimmed one against real Kdenlive.

   Found 2026-09-20 by hive-creator.conformance-test, which noticed only that
   the catalog did not declare the two params; reading the fork explained why.

   The HTTP client now turns the trim into a following :clip/resize. What a
   double can prove is that the REQUESTS it makes are the ones that produce
   the headless result; a live Kdenlive is card 20260913200513-6294eb30."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-kdenlive.kdenlive.client :as client]
            [hive-kdenlive.kdenlive.document :as document]
            [hive-kdenlive.mlt.timeline :as timeline]
            [hive-kdenlive.kdenlive.json :as json])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def ^:private in-frame 0)
(def ^:private out-frame 449)
(def ^:private expected-duration (inc (- out-frame in-frame)))

;; ---------------------------------------------------------------------------
;; The HTTP side

(defn- with-fork
  "A server that answers every route with {\"id\":7}, recording each request."
  [f]
  (let [seen   (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [body  (slurp (.getRequestBody ex) :encoding "UTF-8")
                              bytes (.getBytes "{\"id\":7}" StandardCharsets/UTF_8)]
                          (swap! seen conj {:method (.getRequestMethod ex)
                                            :path   (.getPath (.getRequestURI ex))
                                            :body   (:ok (json/read body))})
                          (.sendResponseHeaders ex 200 (count bytes))
                          (with-open [out (.getResponseBody ex)] (.write out bytes))))))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/api/v1") seen)
      (finally (.stop server 0)))))

(deftest over-http-a-trim-becomes-an-insert-then-a-resize-test
  (with-fork
    (fn [base seen]
      (let [res (client/-call (client/http-kdenlive base) :timeline/insert-clip
                              {:binId "3" :trackId 1 :position 0
                               :in in-frame :out out-frame})]
        (testing "the caller still gets the inserted clip's id, not the resize's answer"
          (is (nil? (:error res)) (pr-str res))
          (is (= 7 (get-in res [:ok :id]))
              "the route's :result is \"id\", so it is unwrapped under :id"))
        (let [[insert resize] @seen]
          (testing "two requests, in order"
            (is (= 2 (count @seen)) (pr-str @seen)))
          (testing "the insert carries only what the fork binds"
            (is (= ["POST" "/api/v1/timeline/clips"] [(:method insert) (:path insert)]))
            (is (= {"binId" "3" "trackId" 1 "position" 0} (:body insert))
                "in and out must NOT be sent: the fork ignores them silently"))
          (testing "the resize carries the length the caller asked for"
            (is (= ["PUT" "/api/v1/timeline/clips/7"] [(:method resize) (:path resize)]))
            (is (= expected-duration (get (:body resize) "duration")))
            (is (= 7 (get (:body resize) "clipId")))))))))

(deftest over-http-an-untrimmed-insert-is-still-one-request-test
  (with-fork
    (fn [base seen]
      (client/-call (client/http-kdenlive base) :timeline/insert-clip
                    {:binId "3" :trackId 1 :position 0})
      (is (= 1 (count @seen)) "no trim asked for, so no resize"))))

;; ---------------------------------------------------------------------------
;; The headless side

(deftest headlessly-a-trim-lands-a-clip-of-the-same-length-test
  ;; Driven through `apply-verb`, the same dispatch the document transport
  ;; uses, with a stubbed probe. Importing through the document would need a
  ;; real media file on disk and an ffprobe to measure it; what is under test
  ;; is the trim, not the probe.
  (let [ctx  {:probe (fn [_] {:length 3000})}
        step (fn [state route params]
               (let [{:keys [ok error]} (timeline/apply-verb state route params ctx)]
                 (is (nil? error) (str route " failed: " (pr-str error)))
                 ok))
        {s1 :state}          (step nil :project/new {:name "equiv"})
        {s2 :state r2 :result} (step s1 :media/import {:paths ["/tmp/a.mp4"]})
        bin                  (first (:ids r2))
        {s3 :state r3 :result} (step s2 :timeline/add-track {:name "V1" :isAudio false})
        track                (:id r3)
        {s4 :state r4 :result} (step s3 :timeline/insert-clip
                                     {:binId bin :trackId track :position 0
                                      :in in-frame :out out-frame})]
    (is (some? (:id r4)) "the headless transport answers an :id, as HTTP now does too")
    (testing "the clip on the timeline is exactly the requested length"
      (let [{clips :result} (step s4 :timeline/track-clips {:id track})
            clip (first (:clips clips))]
        (is (= 1 (count (:clips clips))) (pr-str clips))
        (is (= expected-duration (inc (- (:out clip) (:in clip))))
            (str "expected " expected-duration " frames, got " (pr-str clip)))))))
