(ns hive-kdenlive.kdenlive.client
  "HTTP transport to the Kdenlive scripting fork — the JVM boundary.

   Strata:
     Pure      routes/request        (kdenlive.routes, portable)
     Port      IKdenlive             — call a catalog route, get data back
     Boundary  HttpKdenlive          — java.net.http, JSON wire
     Seam      *kdenlive*            — read at call time; rebind in tests

   The wire encoding is JSON. Requests carry the route's :body as a JSON
   object, built by `->json` here.

   Responses are NOT decoded. `-call` answers
   {:ok {:status int :body \"<raw JSON string>\"}}, because this namespace has
   no JSON reader: `->json` writes, and nothing reads. A caller that needs a
   field out of the answer (hive-creator's :finish binds [:ids 0] and [:id]
   to thread ids between steps) therefore cannot use this transport yet; the
   headless document transport answers parsed data and is what such callers
   run against today. This docstring previously claimed responses came back
   \"decoded to Clojure data with keyword keys\", which was never true.

   Tracked as a card: give this transport a JSON reader, and with it the
   :timeline/insert-clip trim, which the fork cannot do in one call (its
   scriptInsertClip binds binId/trackId/position only) and which therefore
   needs a following :clip/resize to match what the headless transport does."
  (:require [clojure.string :as str]
            [hive-kdenlive.kdenlive.routes :as routes]
            [hive-kdenlive.kdenlive.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.net.http HttpResponse]))

;; ---------------------------------------------------------------------------
;; Wire encoding — minimal JSON, no deps (bodies are flat string/number maps)

(defn ->json
  "Encode `m` as JSON. Delegates to `hive-kdenlive.kdenlive.json/write`.

   Kept as a name because callers and tests use it. It used to live here and
   handled a FLAT map of scalars only, rendering anything else with `str`,
   which turned `:paths [\"a\" \"b\"]` into a Clojure literal and `:params
   {...}` into one too. Those are the shapes hive-creator sends."
  [m]
  (json/write m))

;; ---------------------------------------------------------------------------
;; Port

(defprotocol IKdenlive
  (-call [client route-id params]
    "Invoke catalog route `route-id` with `params`.
     {:ok data} | {:error :kdenlive/... :status int}"))

;; ---------------------------------------------------------------------------
;; Boundary

(defn- answer
  "The fork's reply as the port's data.

   `:status` and `:body` are kept exactly as they were, so every existing
   caller and the wire suite still read what they read. What is new is the
   DECODED payload: the route's `:result` names one wire field, and that field
   is unwrapped under its own keyword, so a caller binds `[:id]` or `[:ids 0]`
   here the same way it does against the headless document transport. Before
   2026-09-20 nothing was decoded at all and those binds answered nil."
  [route-id status body]
  (let [parsed (json/read body)
        result (:result (routes/route route-id))]
    (cond-> {:status status :body body}
      (contains? parsed :ok)
      (assoc :parsed (:ok parsed))

      (and (contains? parsed :ok) (map? (:ok parsed)) result
           (contains? (:ok parsed) result))
      (assoc (keyword result) (get (:ok parsed) result)))))

(defn- send!
  "One request. Split out of `-call` so the insert-clip trim can make two."
  [{:keys [base-url http-client secret]} route-id params]
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
            builder (.header builder "Content-Type" "application/json")
            ;; The fork answers 401 without it when KDENLIVE_SCRIPTING_SECRET is set.
            builder (if (str/blank? secret) builder (.header builder "X-Kdenlive-Secret" secret))
            ;; The hint is load-bearing. The runtime class is the JDK's
            ;; package-private HttpResponseImpl, which reflection cannot call
            ;; methods on: unhinted, (.status resp) threw "No matching field
            ;; found: status" on every response.
            ^HttpResponse resp (.send ^HttpClient http-client
                                      (.build builder)
                                      (HttpResponse$BodyHandlers/ofString))
            status  (.statusCode resp)]
        (if (<= 200 status 299)
          {:ok (answer route-id status (.body resp))}
          {:error :kdenlive/http-error :status status :body (.body resp)})))))

(defrecord HttpKdenlive [base-url http-client secret]
  IKdenlive
  (-call [this route-id params]
    (let [{:keys [in out]} params]
      (if (and (= :timeline/insert-clip route-id) (some? in) (some? out))
        ;; The trim, made to mean the same thing on both transports.
        ;;
        ;; The headless document implements :in/:out directly. The fork cannot:
        ;; its scriptInsertClip binds binId, trackId and position and nothing
        ;; else, and its HTTP session reads only declared params, so sending
        ;; them here did nothing and said nothing. The clip is inserted, then
        ;; resized to the length the caller asked for, which is what
        ;; PUT /timeline/clips/{id} exists for.
        (let [inserted (send! this route-id (dissoc params :in :out))]
          (if (:error inserted)
            inserted
            (let [id (get-in inserted [:ok :id])]
              (if (nil? id)
                (assoc inserted :warning :kdenlive/trim-skipped
                       :reason "the insert answered no id, so the clip could not be resized")
                (let [resized (send! this :clip/resize
                                     {:id id :clipId id :duration (inc (- out in))})]
                  (if (:error resized)
                    resized
                    ;; Answer the INSERT, so a caller binding [:id] gets the
                    ;; clip it inserted rather than the resize's duration.
                    inserted))))))
        (send! this route-id params)))))

(defn endpoint
  "Where the scripting fork listens, from an environment map, read with the
   fork's OWN variable names (kdenlive/src/scripting/scriptingserver.cpp), so
   one environment configures both sides:

     KDENLIVE_SCRIPTING_ADDRESS  default 127.0.0.1
     KDENLIVE_SCRIPTING_PORT     default 9876
     KDENLIVE_SCRIPTING_SECRET   default none; sent as X-Kdenlive-Secret

   The base URL carries /api/v1. The fork also accepts paths without it, but
   /api/v1 is the documented prefix and survives a stricter server."
  [env]
  (let [address (or (not-empty (get env "KDENLIVE_SCRIPTING_ADDRESS")) "127.0.0.1")
        port    (or (not-empty (get env "KDENLIVE_SCRIPTING_PORT")) "9876")]
    {:base-url (str "http://" address ":" port "/api/v1")
     :secret   (not-empty (get env "KDENLIVE_SCRIPTING_SECRET"))}))

(defn http-kdenlive
  "An IKdenlive against the scripting fork. With no argument, where the fork's
   own environment variables say it listens (see `endpoint`); with a base URL
   and optional secret, exactly there."
  ([] (let [{:keys [base-url secret]} (endpoint (System/getenv))]
        (http-kdenlive base-url secret)))
  ([base-url] (http-kdenlive base-url nil))
  ([base-url secret]
   (->HttpKdenlive base-url (.build (HttpClient/newBuilder)) secret)))

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
