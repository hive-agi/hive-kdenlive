(ns hive-kdenlive.addon
  "IAddon boundary for hive.kdenlive — exposes the MLT core, headless melt
   rendering, and the Kdenlive HTTP transport as MCP tools.

   Pure constructor, no registration side effects: the host drives
   register!/initialize! after resolving `addon-ctor` from the manifest."
  (:require [hive-addon.protocol :as addon]
            [hive-kdenlive.kdenlive.client :as kdenlive]
            [hive-kdenlive.kdenlive.routes :as routes]
            [hive-kdenlive.render :as render]
            [clojure.string :as str]))

(def addon-id-value "hive.kdenlive")

;; ---------------------------------------------------------------------------
;; Tool handlers — thin: seam dispatch, data in, data out

(defn- handle-render
  [{:strs [mlt_xml out profile]}]
  (cond
    (not (string? mlt_xml)) {:error :kdenlive/bad-params :message "mlt_xml must be a string"}
    (not (string? out))     {:error :kdenlive/bad-params :message "out must be a path string"}
    :else
    (render/render! mlt_xml out (cond-> {} (string? profile) (assoc :profile profile)))))

(defn- handle-kdenlive-call
  [{:strs [route params] :as _in}]
  (if-not (string? route)
    {:error :kdenlive/bad-params :message "route must be a catalog id string"}
    (kdenlive/call (keyword (clojure.string/replace route "_" "/"))
                   (or params {}))))

(defn- handle-routes [_]
  {:ok {:routes (mapv #(select-keys % [:id :method :path]) routes/catalog)}})

(defn- handle-ping [_]
  {:ok {:kdenlive (kdenlive/ping)
        :melt     (some? (render/which "melt"))}})

;; ---------------------------------------------------------------------------
;; Tool definitions

(def tool-defs
  [{:name        "render"
    :description "Render an MLT XML document to a video file via headless melt."
    :inputSchema {:type       "object"
                  :properties {"mlt_xml"  {:type "string" :description "MLT XML document"}
                               "out"      {:type "string" :description "output file path"}
                               "profile"  {:type "string" :description "avformat profile (optional)"}}
                  :required   ["mlt_xml" "out"]}
    :handler     handle-render}
   {:name        "kdenlive_call"
    :description "Invoke a route of the Kdenlive scripting fork's HTTP transport."
    :inputSchema {:type       "object"
                  :properties {"route"  {:type "string" :description "catalog id, e.g. project_open"}
                               "params" {:type "object" :description "route params"}}
                  :required   ["route"]}
    :handler     handle-kdenlive-call}
   {:name        "routes"
    :description "List the Kdenlive route catalog."
    :inputSchema {:type "object" :properties {}}
    :handler     handle-routes}
   {:name        "ping"
    :description "Probe melt on PATH and the Kdenlive scripting fork."
    :inputSchema {:type "object" :properties {}}
    :handler     handle-ping}])

;; ---------------------------------------------------------------------------
;; IAddon

(defrecord HiveKdenliveAddon [config]
  addon/IAddon
  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:tools :health-reporting})
  (initialize! [_ _cfg]
    {:success? true :errors []
     :metadata {:tools (mapv :name tool-defs)
                :routes (count routes/catalog)}})
  (shutdown! [_] nil)
  (tools [_] tool-defs)
  (schema-extensions [_] [])
  (health [_]
    {:status :ok
     :details {:melt-on-path? (some? (render/which "melt"))
               :routes (count routes/catalog)}})
  (excluded-tools [_] #{})
  (hooks [_] {}))

(defn addon-ctor
  "Pure constructor for the `hive.kdenlive` IAddon — (config -> IAddon).
   The mounter resolves this via :addon/init-fn."
  [config]
  (->HiveKdenliveAddon config))
