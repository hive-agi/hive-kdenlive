(ns hive-kdenlive.kdenlive.client
  "HTTP transport to the Kdenlive scripting fork — the JVM boundary.

   Strata:
     Pure      routes/request        (kdenlive.routes, portable)
     Port      IKdenlive             — call a catalog route, get data back
     Boundary  HttpKdenlive          — java.net.http, JSON wire
     Seam      *kdenlive*            — read at call time; rebind in tests

   The wire encoding is JSON; requests carry the route's :body as the JSON
   object and responses are decoded to Clojure data with keyword keys."
  (:require [clojure.string :as str]
            [hive-kdenlive.kdenlive.routes :as routes])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

;; ---------------------------------------------------------------------------
;; Wire encoding — minimal JSON, no deps (bodies are flat string/number maps)

(defn- json-escape [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn ->json
  "Encode a flat map as a JSON object string. Values: string/number/boolean."
  [m]
  (str "{"
       (str/join "," (map (fn [[k v]]
                            (str "\"" (json-escape (name k)) "\":"
                                 (cond
                                   (string? v)  (str "\"" (json-escape v) "\"")
                                   (boolean? v) (str v)
                                   :else        (str v))))
                          m))
       "}"))

;; ---------------------------------------------------------------------------
;; Port

(defprotocol IKdenlive
  (-call [client route-id params]
    "Invoke catalog route `route-id` with `params`.
     {:ok data} | {:error :kdenlive/... :status int}"))

;; ---------------------------------------------------------------------------
;; Boundary

(defrecord HttpKdenlive [base-url http-client]
  IKdenlive
  (-call [_ route-id params]
    (let [{:keys [ok error] :as res} (routes/request route-id params)]
      (if error
        res
        (let [builder (-> (HttpRequest/newBuilder)
                          (.uri (URI/create (str base-url (:path ok)))))
              builder (case (:method ok)
                        :get    (.GET builder)
                        :delete (.DELETE builder)
                        ;; :post and :put both carry the JSON body
                        (.method builder
                                 (str/upper-case (name (:method ok)))
                                 (HttpRequest$BodyPublishers/ofString
                                  (->json (:body ok)) StandardCharsets/UTF_8)))
              resp    (.send ^HttpClient http-client
                             (.build (.header builder "Content-Type" "application/json"))
                             (HttpResponse$BodyHandlers/ofString))
              status  (.status resp)]
          (if (<= 200 status 299)
            {:ok {:status status :body (.body resp)}}
            {:error :kdenlive/http-error :status status :body (.body resp)}))))))

(defn http-kdenlive
  "An IKdenlive against the scripting fork at `base-url` (default: localhost
   on Kdenlive's scripting port)."
  ([] (http-kdenlive "http://localhost:4700"))
  ([base-url]
   (->HttpKdenlive base-url (.build (HttpClient/newBuilder)))))

;; ---------------------------------------------------------------------------
;; Facade — seam read at call time

(def ^:dynamic *kdenlive*
  "The IKdenlive `call` dispatches through. Rebind with a recording stub in
   tests; the delay keeps the default lazy so importing this ns never opens a
   connection."
  (delay (http-kdenlive)))

(defn call
  "Invoke catalog route `route-id` with `params` through `*kdenlive*`."
  [route-id & {:as params}]
  (-call (if (delay? *kdenlive*) @*kdenlive* *kdenlive*) route-id (or params {})))

(defn ping
  "True when the scripting fork answers. GET /project is the cheapest read
   the route table registers, so it doubles as the health probe."
  []
  (not (:error (call :project/info))))
