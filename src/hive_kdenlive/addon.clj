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
            [hive-kdenlive.kdenlive.document :as document]
            [hive-help.core :as help]))

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

(defn- id-str
  "A route id as \"namespace/name\".

   hive-help renders with `name`, which drops a keyword's namespace, so
   `:project/info` would read as `info` and `:media/list` as `list`. Every id
   handed to hive-help goes through here first."
  [id]
  (if (keyword? id)
    (str (when (namespace id) (str (namespace id) "/")) (name id))
    (str id)))

(defn- route-names
  "Every catalog id as a \"namespace/name\" string, for hive-help to list and
   to compute suggestions over."
  []
  (mapv id-str (map :id routes/catalog)))

(defn- actionable
  "A routes refusal, rendered so the reader knows what to do next.

   The portable namespaces answer error VALUES and nothing else: `routes` runs
   on cljw and cljrs, where hive-help does not. Rendering belongs here, at the
   boundary, which is also the only place that knows a caller is looking at an
   MCP tool result rather than at a Clojure map.

   Without this, `kdenlive_call` with a mistyped route answered
   {:error :routes/unknown-route :route :timeline/insert-clips} and left the
   reader to go and find the catalog."
  [answer]
  (case (:error answer)
    :routes/unknown-route
    (assoc answer :message
           (help/unknown-command
            {:tool           "kdenlive_call"
             :command        (id-str (:route answer))
             :valid-commands (route-names)
             :examples       ["timeline/insert-clip" "project/open" "render/start"]}))

    :routes/undeclared-params
    (let [{:keys [route undeclared declared]} answer]
      (assoc answer :message
             (help/join-lines
              (str "Route `" (id-str route) "` does not declare "
                   (str/join ", " (map help/backtick undeclared))
                   (if (= 1 (count undeclared)) " as a param." " as params."))
              ""
              "It declares:"
              (help/bullet-list (map name declared))
              ""
              (str "HINT: "
                   (str/join "; "
                             (for [u undeclared
                                   :let [near (help/suggest u declared 2)]
                                   :when (seq near)]
                               (str (help/backtick u) " -> did you mean "
                                    (str/join " or " (map help/backtick near)) "?")))))))

    :routes/missing-params
    (assoc answer :message
           (help/join-lines
            (str "Route `" (id-str (:route answer)) "` needs "
                 (str/join ", " (map help/backtick (:missing answer)))
                 " and they were not given.")
            ""
            (str "HINT: params use the fork's own camelCase wire names, so "
                 (help/backtick "binId") " rather than " (help/backtick "bin-id") ".")))

    answer))

(defn- wire-params
  "MCP hands params with STRING keys; the route catalog and the headless verbs
   both speak KEYWORDS.

   Nothing bridged the two until 2026-09-20, and both transports suffered for
   it in their own way. `routes/request` asks `(contains? params :binId)`, so
   over HTTP every required param of every call read as missing. The headless
   verbs destructure `{:keys [name isAudio]}`, so a call arriving from MCP
   bound nil and carried on: `timeline/add-track` really did create tracks
   named nil, and nothing failed.

   The fork's own spelling is camelCase, so the keyword is the wire name
   verbatim: \"binId\" -> :binId, never :bin-id."
  [params]
  (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v])) (or params {})))

(defn- handle-kdenlive-call
  "One catalog route, answered by the fork over HTTP, or, when `project` names
   a timeline file, by the headless :document transport. Same route ids, same
   params either way.

   Params are keywordized on the way in and every answer goes through
   `actionable`, so a refusal from the route catalog carries a message the
   reader can act on instead of only a keyword."
  [{:strs [route params project] :as _in}]
  (cond
    (not (string? route))
    {:error :kdenlive/bad-params :message "route must be a catalog id string"}

    (and (some? project) (not (string? project)))
    {:error :kdenlive/bad-params :message "project must be a path string"}

    (string? project)
    (actionable (kdenlive/-call (document/document-kdenlive project)
                                (route-id route) (wire-params params)))

    :else
    (actionable (kdenlive/call (route-id route) (wire-params params)))))

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
  "Every tool is named kdenlive_<verb>.

   Four of these were once bare: render, routes, ping and inspect_project.
   An MCP host mounts many addons into ONE tool namespace, so a bare `ping`
   or `render` is a name this addon has no claim to, and the fleet spells
   every other addon's tools gimp_*, creator_*, publisher_*. Renamed
   2026-09-20; nothing outside this repo referenced the old names."
  [{:name        "kdenlive_render"
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
   {:name        "kdenlive_routes"
    :description "List the Kdenlive route catalog."
    :inputSchema {:type "object" :properties {}}
    :handler     handle-routes}
   {:name        "kdenlive_ping"
    :description "Probe melt on PATH and the Kdenlive scripting fork."
    :inputSchema {:type "object" :properties {}}
    :handler     handle-ping}
   {:name        "kdenlive_inspect_project"
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
