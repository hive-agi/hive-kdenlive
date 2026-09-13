(ns hive-kdenlive.kdenlive.routes
  "Route catalog for the Kdenlive scripting fork's HTTP transport — pure data.

   The catalog is the single source: each route names its id, HTTP method,
   path template, and required/optional params. The client boundary walks
   this data; adding an endpoint is a catalog entry, never new client code.

   Portable: core + string only."
  (:require [clojure.string :as str]))

(def catalog
  "Every endpoint the transport knows. Path templates use {param} holes,
   filled from the call's :params."
  [{:id :server/ping :method :get :path "/ping"}
   {:id :project/open :method :post :path "/project/open"
    :params {:required #{:path}}}
   {:id :project/save :method :post :path "/project/save"}
   {:id :project/close :method :post :path "/project/close"}
   {:id :project/current :method :get :path "/project/current"}
   {:id :timeline/insert-clip :method :post :path "/timeline/insertClip"
    :params {:required #{:resource :track :position}
             :optional #{:in :out}}}
   {:id :timeline/insert-blank :method :post :path "/timeline/insertBlank"
    :params {:required #{:track :position :length}}}
   {:id :timeline/delete-zone :method :post :path "/timeline/deleteZone"
    :params {:required #{:track :in :out}}}
   {:id :timeline/insert-track :method :post :path "/timeline/insertTrack"
    :params {:required #{:index} :optional #{:audio?}}}
   {:id :effect/append :method :post :path "/effect/append"
    :params {:required #{:clip-id :mlt-service} :optional #{:args}}}
   {:id :render/start :method :post :path "/render/start"
    :params {:optional #{:preset :out :in :out-point}}}
   {:id :render/status :method :get :path "/render/status"}])

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
