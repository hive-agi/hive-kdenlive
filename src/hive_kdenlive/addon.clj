(ns hive-kdenlive.addon
  "IAddon boundary for hive.kdenlive — exposes the MLT core, headless melt
   rendering, and the Kdenlive HTTP transport as MCP tools.

   Pure constructor, no registration side effects: the host drives
   register!/initialize! after resolving `addon-ctor` from the manifest."
  (:require [hive-addon.protocol :as addon]
            [hive-kdenlive.kdenlive.client :as kdenlive]
            [hive-kdenlive.kdenlive.routes :as routes]
            [hive-kdenlive.mlt.project :as project]
            [hive-kdenlive.render :as render]
            [clojure.string :as str]
            [hive-kdenlive.kdenlive.document :as document]))

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

(defn- route-id
  "Normalize an MCP caller's route string to a catalog id keyword.
   Canonical form (\"timeline/insert-clip\") passes through; otherwise the
   FIRST underscore is the namespace separator (\"project_open\" ->
   :project/open) and later underscores stay — ids never contain one."
  [s]
  (keyword (if (str/includes? s "/")
             s
             (str/replace-first s "_" "/"))))

(defn- handle-kdenlive-call
  "One catalog route, answered by the fork over HTTP, or, when `project` names
   a timeline file, by the headless :document transport. Same route ids, same
   params either way."
  [{:strs [route params project] :as _in}]
  (cond
    (not (string? route))
    {:error :kdenlive/bad-params :message "route must be a catalog id string"}

    (and (some? project) (not (string? project)))
    {:error :kdenlive/bad-params :message "project must be a path string"}

    (string? project)
    (kdenlive/-call (document/document-kdenlive project) (route-id route) (or params {}))

    :else
    (kdenlive/call (route-id route) (or params {}))))

(defn- handle-routes [_]
  {:ok {:routes (mapv #(select-keys % [:id :method :path]) routes/catalog)}})

(defn- handle-ping [_]
  {:ok {:kdenlive (kdenlive/ping)
        :melt     (some? (render/which "melt"))}})

(defn- handle-inspect-project
  [{:strs [path] :as _in}]
  (cond
    (not (string? path))
    {:error :kdenlive/bad-params :message "path must be a string"}

    (not (.exists (java.io.File. path)))
    {:error :kdenlive/file-not-found :path path}

    :else
    (let [{:keys [ok error] :as res} (project/summarize (slurp path))]
      (if error
        res
        {:ok (assoc ok :duration-frames (project/duration-frames ok))}))))

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
    :description (str "Invoke a route of the Kdenlive route catalog. Without `project` it goes to the "
                      "Kdenlive scripting fork over HTTP. With `project` (a path to a .hkd.edn timeline, "
                      "created on first edit) the same route is answered headlessly: media/import, "
                      "timeline/add-track, timeline/insert-clip, timeline/insert-space, "
                      "timeline/zone-extract, project/info, render/start and the reads; an .mlt melt "
                      "renders is written beside the project after every edit.")
    :inputSchema {:type       "object"
                  :properties {"route"   {:type "string" :description "catalog id, e.g. timeline/insert-clip or project_open"}
                               "params"  {:type "object" :description "route params, as the fork names them (binId, trackId, position, ...)"}
                               "project" {:type "string" :description "optional: timeline file for the headless transport"}}
                  :required   ["route"]}
    :handler     handle-kdenlive-call}
   {:name        "routes"
    :description "List the Kdenlive route catalog."
    :inputSchema {:type "object" :properties {}}
    :handler     handle-routes}
   {:name        "ping"
    :description "Probe melt on PATH and the Kdenlive scripting fork."
    :inputSchema {:type "object" :properties {}}
    :handler     handle-ping}
   {:name        "inspect_project"
    :description "Summarize a .kdenlive/MLT project file: profile, bin, tracks, duration."
    :inputSchema {:type       "object"
                  :properties {"path" {:type "string" :description "project file path"}}
                  :required   ["path"]}
    :handler     handle-inspect-project}])

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
