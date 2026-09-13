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
   (kdenlive-mcp/kdenlive-server/src/scripting/routetable.cpp, 2026-09-13):
   every :method/:path below is registered there. Params use the server's
   camelCase wire names."
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
   ;; media bin
   {:id :media/import :method :post :path "/media/import"
    :params {:required #{:paths} :optional #{:folderId}} :result "ids"}
   {:id :media/list :method :get :path "/media"}
   ;; timeline
   {:id :timeline/tracks :method :get :path "/timeline/tracks"}
   {:id :timeline/add-track :method :post :path "/timeline/tracks"
    :params {:required #{:name :isAudio}} :result "id"}
   {:id :timeline/delete-track :method :delete :path "/timeline/tracks/{id}"
    :params {:required #{:id}} :result "success"}
   {:id :timeline/track-clips :method :get :path "/timeline/tracks/{id}/clips"
    :params {:required #{:id}} :result "clips"}
   {:id :timeline/insert-clip :method :post :path "/timeline/clips"
    :params {:required #{:binId :trackId :position}} :result "id"}
   {:id :timeline/insert-clips-batch :method :post :path "/timeline/clips/batch"
    :params {:required #{:binIds :trackId :startPosition}} :result "ids"}
   {:id :timeline/insert-space :method :post :path "/timeline/space"
    :params {:required #{:trackId :position :duration} :optional #{:allTracks}}
    :result "success"}
   {:id :timeline/zone-extract :method :post :path "/timeline/zone/extract"
    :params {:required #{:inFrame :outFrame} :optional #{:liftOnly}}
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

(defn- fill-path
  "Substitute {param} holes in `template` from `params` (string values only)."
  [template params]
  (reduce (fn [p [k v]]
            (clojure.string/replace p (str "{" (name k) "}") (str v)))
          template
          params))

(defn request
  "Build a request map from a route id and call params.
   Returns {:ok {:method :get|:post :path string :body map-or-nil}}
   or {:error :routes/unknown-route | :routes/missing-params ...}."
  [id params]
  (if-let [entry (route id)]
    (let [params  (or params {})
          missing (seq (missing-params entry params))]
      (if missing
        {:error :routes/missing-params :route id :missing (vec missing)}
        {:ok {:method (:method entry)
              :path   (fill-path (:path entry) params)
              :body   (when (= :post (:method entry)) params)}}))
    {:error :routes/unknown-route :route id}))
