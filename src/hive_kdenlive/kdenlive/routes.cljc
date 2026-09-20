(ns hive-kdenlive.kdenlive.routes
  "Route catalog for the Kdenlive scripting fork's HTTP transport — pure data.

   The catalog is the single source: each route names its id, HTTP method,
   path template, and required/optional params. The client boundary walks
   this data; adding an endpoint is a catalog entry, never new client code.

   Portable: core + string only."
  (:require [clojure.string :as str]))

(def catalog
  "Every endpoint the transport knows. Path templates use {param} holes,
   filled from the call's :params. :result names the response field the
   server wraps the payload in, where the C++ names one.

   Verified against the scripting fork's route table
   (kdenlive-mcp/kdenlive-server/src/scripting/routetable.cpp, 2026-09-13,
   extended 2026-09-15, re-read 2026-09-20): every :method/:path below is
   registered there. Params use the server's camelCase wire names; a {id}
   path hole is the fork's clipId.

   A row declares what a CALLER may send, which is not always what the fork
   binds: see :timeline/insert-clip, where the headless transport accepts two
   params the HTTP one has to reach by a second call."
  [;; project
   {:id :project/info :method :get :path "/project"}
   {:id :project/new :method :post :path "/project/new"
    :params {:required #{:name}} :result "path"}
   {:id :project/open :method :post :path "/project/open"
    :params {:required #{:path}} :result "success"}
   {:id :project/save :method :post :path "/project/save" :result "success"}
   {:id :project/save-as :method :post :path "/project/save-as"
    :params {:required #{:path}} :result "success"}
   {:id :project/undo :method :post :path "/project/undo" :result "success"}
   {:id :project/redo :method :post :path "/project/redo" :result "success"}
   {:id :project/profile :method :put :path "/project/profile"
    :params {:required #{:width :height :fpsNum :fpsDen}} :result "success"}
   ;; media bin
   {:id :media/import :method :post :path "/media/import"
    :params {:required #{:paths} :optional #{:folderId}} :result "ids"}
   {:id :media/list :method :get :path "/media"}
   {:id :media/create-title :method :post :path "/media/titles"
    :params {:required #{:xml :duration} :optional #{:name :folderId}} :result "id"}
   ;; timeline
   {:id :timeline/tracks :method :get :path "/timeline/tracks"}
   {:id :timeline/add-track :method :post :path "/timeline/tracks"
    :params {:required #{:name :isAudio}} :result "id"}
   {:id :timeline/delete-track :method :delete :path "/timeline/tracks/{id}"
    :params {:required #{:id}} :result "success"}
   {:id :timeline/track-clips :method :get :path "/timeline/tracks/{id}/clips"
    :params {:required #{:id}} :result "clips"}
   ;; insert-clip: :in and :out trim the clip as it is placed, and the two
   ;; transports reach that differently. The headless document implements
   ;; them directly (mlt.timeline/insert-clip passes them to `place`). The
   ;; fork does NOT: scriptInsertClip binds binId, trackId and position and
   ;; nothing else, and httpsession.cpp reads only declared params, so extra
   ;; body keys are neither used nor rejected. They are declared OPTIONAL
   ;; here because a caller may legitimately send them, and kdenlive.client
   ;; turns them into a following :clip/resize so both transports answer the
   ;; same timeline. Dropping them silently is what must never happen again.
   {:id :timeline/insert-clip :method :post :path "/timeline/clips"
    :params {:required #{:binId :trackId :position} :optional #{:in :out}}
    :result "id"}
   {:id :timeline/insert-clips-batch :method :post :path "/timeline/clips/batch"
    :params {:required #{:binIds :trackId :startPosition}} :result "ids"}
   {:id :timeline/insert-space :method :post :path "/timeline/space"
    :params {:required #{:trackId :position :duration} :optional #{:allTracks}}
    :result "success"}
   {:id :timeline/zone-extract :method :post :path "/timeline/zone/extract"
    :params {:required #{:inFrame :outFrame} :optional #{:liftOnly}}
    :result "success"}
   ;; clip properties
   {:id :clip/opacity :method :put :path "/timeline/clips/{id}/opacity"
    :params {:required #{:id :opacity}} :result "success"}
   {:id :clip/volume :method :put :path "/timeline/clips/{id}/volume"
    :params {:required #{:id :dB}} :result "success"}
   {:id :clip/audio-fade :method :put :path "/timeline/clips/{id}/audio/fade"
    :params {:required #{:id :fadeIn :fadeOut}} :result "success"}
   {:id :clip/transform-keyframe :method :post :path "/timeline/clips/{id}/transform/keyframes"
    :params {:required #{:id :frame :x :y :width :height} :optional #{:opacity}}
    :result "success"}
   ;; PUT /timeline/clips/{id} is ONE lambda in the fork with two branches:
   ;; trackId + position moves the clip (scriptMoveClip), duration resizes it
   ;; (scriptResizeClip, fromRight defaulting true). Two ids here, one path,
   ;; so a caller states which branch it means instead of relying on which
   ;; keys it happened to include.
   {:id :clip/resize :method :put :path "/timeline/clips/{id}"
    :params {:required #{:id :clipId :duration} :optional #{:fromRight}}
    :result "duration"}
   {:id :clip/move :method :put :path "/timeline/clips/{id}"
    :params {:required #{:id :clipId :trackId :position}}
    :result "success"}
   ;; effects — the server reads clipId/effectId from the BODY; {id} in the
   ;; path is vestigial but registered, so :id is required too
   {:id :effects/available :method :get :path "/effects/available" :result "effects"}
   {:id :clip/append-effect :method :post :path "/timeline/clips/{id}/effects"
    :params {:required #{:id :clipId :effectId} :optional #{:params}}
    :result "success"}
   ;; render — POST /render with :outputFile (+ optional :preset :inFrame
   ;; :outFrame :params), or a bare {:url ...}
   {:id :render/start :method :post :path "/render"
    :params {:optional #{:outputFile :preset :inFrame :outFrame :params :url}}
    :result "success"}
   {:id :render/jobs :method :get :path "/render/jobs" :result "jobs"}
   {:id :render/abort :method :post :path "/render/jobs/abort"
    :params {:required #{:path}} :result "success"}
   {:id :render/presets :method :get :path "/render/presets" :result "presets"}
   ;; playback
   {:id :playback/play :method :post :path "/playback/play"}
   {:id :playback/pause :method :post :path "/playback/pause"}
   {:id :playback/seek :method :post :path "/playback/seek"
    :params {:required #{:frame}}}
   {:id :playback/position :method :get :path "/playback/position"}])

(def by-id
  "Route id -> catalog entry."
  (into {} (map (fn [r] [(:id r) r])) catalog))

(defn route
  "Catalog entry for `id`, or nil."
  [id]
  (get by-id id))

(defn- missing-params
  [entry params]
  (remove #(contains? params %) (get-in entry [:params :required])))

(defn- path-holes
  "The {param} holes in a route's path, as keywords. A hole is a declared
   param even when :params does not repeat it."
  [entry]
  (into #{} (map keyword) (re-seq #"(?<=\{)[a-zA-Z]+(?=\})" (or (:path entry) ""))))

(defn declared-params
  "Every param key `entry` accepts: required, optional and path holes."
  [entry]
  (into (path-holes entry)
        (concat (get-in entry [:params :required])
                (get-in entry [:params :optional]))))

(defn- undeclared-params
  "Keys in `params` the route does not declare, sorted.

   A route entry is the single source for its own surface, so a param it does
   not name must not reach a transport. Silence here is how creator's :in and
   :out rode along to the fork for weeks: the HTTP body carried them, the
   fork binds only its own declared params, and nothing on either side said
   a word."
  [entry params]
  (let [declared (declared-params entry)]
    (sort (remove declared (keys params)))))

(defn- fill-path
  "Substitute {param} holes in `template` from `params` (string values only)."
  [template params]
  (reduce (fn [p [k v]]
            (clojure.string/replace p (str "{" (name k) "}") (str v)))
          template
          params))

(defn request
  "Build a request map from a route id and call params.
   Returns {:ok {:method :get|:post|:put|:delete :path string :body map-or-nil}}
   or {:error :routes/unknown-route | :routes/missing-params
              | :routes/undeclared-params ...}.
   :post and :put carry the params as the body: the fork reads PUT bodies too
   (/project/profile, /timeline/clips/{id}/opacity).

   A param the route does not declare is REFUSED rather than passed along.
   The body used to be whatever the caller handed over, so a key the fork
   does not bind was encoded, sent, ignored and never mentioned; that is how
   an insert-clip trim went missing. The catalog is the single source for a
   route's surface, and this is where that claim is enforced."
  [id params]
  (if-let [entry (route id)]
    (let [params      (or params {})
          missing     (seq (missing-params entry params))
          undeclared  (seq (undeclared-params entry params))]
      (cond
        missing
        {:error :routes/missing-params :route id :missing (vec missing)}

        undeclared
        {:error :routes/undeclared-params :route id :undeclared (vec undeclared)
         :declared (vec (sort (declared-params entry)))}

        :else
        {:ok {:method (:method entry)
              :path   (fill-path (:path entry) params)
              :body   (when (contains? #{:post :put} (:method entry)) params)}}))
    {:error :routes/unknown-route :route id}))
