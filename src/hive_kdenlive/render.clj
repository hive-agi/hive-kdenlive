(ns hive-kdenlive.render
  "Headless melt rendering — the JVM-only boundary of the MLT core.

   Strata:
     Pure      melt-argv        — command vector from data
     Port      IRender          — who can render a document
     Boundary  MeltRenderer     — spawns the melt process
     Seam      *renderer*       — read at call time; rebind in tests

   Results are data: {:ok {...}} | {:error kw ...}, same shape as mlt.xml."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-kdenlive.mlt.model :as model]))

;; ---------------------------------------------------------------------------
;; Pure

(defn consumer-map
  "Avformat consumer properties as a {\"key\" \"value\"} map. Takes a map
   (keyword or string keys, string/number/boolean values) or a Kdenlive
   preset string of space-separated key=value pairs (\"vcodec=libx264 crf=18\").
   nil -> {}. => {:ok map} | {:error :render/bad-consumer-option ...}."
  [consumer]
  (cond
    (nil? consumer) {:ok {}}
    (string? consumer)
    (let [pairs (remove str/blank? (str/split (str/trim consumer) #"\s+"))
          bad   (first (remove (fn [p] (re-matches #"[^=]+=.*" p)) pairs))]
      (if bad
        {:error :render/bad-consumer-option :option bad :expected "key=value"}
        {:ok (into {} (map (fn [p] (let [[k v] (str/split p #"=" 2)] [k v]))) pairs)}))
    (map? consumer)
    (let [named (fn [k] (if (keyword? k) (subs (str k) 1) (str k)))
          bad   (first (remove (fn [[k v]]
                                 (and (re-matches #"[A-Za-z_][A-Za-z0-9_.:+-]*" (named k))
                                      (or (string? v) (number? v) (boolean? v))))
                               consumer))]
      (if bad
        {:error :render/bad-consumer-option :option (named (key bad)) :value (val bad)
         :expected "a property name and a string, number or boolean value"}
        {:ok (into {} (map (fn [[k v]] [(named k) (str v)])) consumer)}))
    :else {:error :render/bad-consumer-option :option consumer :expected "a map or a key=value string"}))

(defn consumer-args
  "CONSUMER (see `consumer-map`) as melt key=value args, sorted by key.
   => {:ok [\"k=v\" ...]} | {:error ...}."
  [consumer]
  (let [{:keys [ok] :as res} (consumer-map consumer)]
    (if ok
      {:ok (mapv (fn [[k v]] (str k "=" v)) (sort-by key ok))}
      res)))

(defn melt-argv
  "The melt command vector for rendering `mlt-path` to `out-path`.
   opts: :profile (avformat profile name), :consumer (map of avformat consumer
   properties, emitted as key=value args sorted by key; validate it with
   `consumer-args` first), :extra (seq of raw args, after the consumer ones)."
  [melt-bin mlt-path out-path & {:keys [profile consumer extra] :as _opts}]
  (into (cond-> [melt-bin mlt-path]
          profile (conj (str "profile=" profile)))
        (concat ["-consumer" (str "avformat:" out-path)]
                (:ok (consumer-args consumer))
                extra)))

(defn which
  "Absolute path of `bin` on PATH, or nil."
  [bin]
  (let [path (System/getenv "PATH")]
    (some (fn [dir]
            (let [f (io/file dir bin)]
              (when (.canExecute f) (.getAbsolutePath f))))
          (str/split (or path "") (re-pattern java.io.File/pathSeparator)))))

(defn melt-env
  "Environment entries a melt process needs so its Qt module loads with no
   window system, given the current ENV map.

   MLT's Qt module (qtblend, kdenlivetitle, qtext) refuses to load unless
   DISPLAY or WAYLAND_DISPLAY is set, and then melt still exits 0, rendering
   the word INVALID where each producer should be and skipping each
   transition. QT_QPA_PLATFORM=offscreen alone does not help: MLT checks the
   variable before Qt ever runs. A placeholder DISPLAY plus the offscreen
   platform renders all three with no X server (measured 2026-09-15, melt
   7.22). A render never needs a real window, so offscreen is the default even
   when a display exists; a caller's own QT_QPA_PLATFORM wins."
  [env]
  (cond-> {}
    (str/blank? (get env "QT_QPA_PLATFORM"))
    (assoc "QT_QPA_PLATFORM" "offscreen")

    (and (str/blank? (get env "DISPLAY")) (str/blank? (get env "WAYLAND_DISPLAY")))
    (assoc "DISPLAY" ":hive-kdenlive-offscreen")))

(defn load-failures
  "The lines where melt says it could not create a producer, filter or
   transition. melt exits 0 after them, so they are the only sign the render
   is not the document."
  [stderr]
  (vec (distinct (map str/trim (re-seq #"[^\r\n]*failed to load[^\r\n]*" (or stderr ""))))))

(defn encoding-for
  "Default consumer properties for OUT-PATH by extension, as a `consumer-map`
   map: H.264 in yuv420p with AAC for .mp4/.mov/.m4v, the combination phones
   and social players accept, and faststart so playback begins before the
   download ends. Other extensions get {} (melt's own choice)."
  [out-path]
  (if (re-find #"(?i)\.(mp4|mov|m4v)$" (str out-path))
    {"vcodec" "libx264" "pix_fmt" "yuv420p" "crf" "18" "preset" "medium"
     "acodec" "aac" "ab" "192k" "movflags" "+faststart"}
    {}))

;; ---------------------------------------------------------------------------
;; Port

(defprotocol IRender
  (-render [renderer mlt-path out-path opts]
    "Render the MLT document at mlt-path to out-path.
     Returns {:ok {:out path :argv [...]}} | {:error :render/... :stderr string}"))

;; ---------------------------------------------------------------------------
;; Boundary

(defn run-process
  "Run ARGV with melt-env added to the inherited environment.
   {:exit :stdout :stderr}; both streams are drained concurrently, so a chatty
   melt cannot block on a full pipe."
  [argv]
  (let [pb      (ProcessBuilder. ^java.util.List argv)
        penv    (.environment pb)
        _       (doseq [[k v] (melt-env (into {} (System/getenv)))] (.put penv k v))
        proc    (.start pb)
        out     (future (slurp (.getInputStream proc)))
        err     (future (slurp (.getErrorStream proc)))
        exit    (.waitFor proc)]
    {:exit exit :stdout @out :stderr @err}))

(defrecord MeltRenderer [bin]
  IRender
  (-render [_ mlt-path out-path opts]
    (if-not bin
      {:error :render/melt-not-found :message "melt is not on PATH"}
      (let [argv (apply melt-argv bin mlt-path out-path (mapcat identity opts))
            {:keys [exit stderr]} (run-process argv)
            failures (load-failures stderr)]
        (cond
          (not (zero? exit)) {:error :render/melt-failed :exit exit :stderr stderr}
          (seq failures)     {:error :render/unloadable :failures failures :out out-path :argv argv}
          :else              {:ok {:out out-path :argv argv}})))))

(defn melt-renderer
  "A MeltRenderer bound to the melt on PATH (explicit `bin` overrides)."
  ([] (melt-renderer (which "melt")))
  ([bin] (->MeltRenderer bin)))

;; ---------------------------------------------------------------------------
;; Facade — seam read at call time

(def ^:dynamic *renderer*
  "The IRender implementation `render!` dispatches through. Rebind in tests
   with a recording stub; do not capture at wiring time."
  (delay (melt-renderer)))

(defn render!
  "Render MLT XML (a string) to `out-path` via `*renderer*`. The document is
   written to a temp file because melt consumes paths, not stdin.
   opts: see `melt-argv`. An invalid :consumer is refused before anything runs;
   a valid one reaches the renderer normalized to a `consumer-map` map."
  [mlt-xml out-path & {:as opts}]
  (let [{consumer :ok :as checked} (consumer-map (:consumer opts))]
    (if (:error checked)
      checked
      (let [opts (cond-> (or opts {}) (contains? opts :consumer) (assoc :consumer consumer))
            tmp  (java.io.File/createTempFile "hive-kdenlive-" ".mlt")]
        (try
          (spit tmp mlt-xml)
          (-render (if (delay? *renderer*) @*renderer* *renderer*)
                   (.getAbsolutePath tmp) out-path opts)
          (finally (.delete tmp)))))))

(defn render-doc!
  "Like render! but takes a model node tree."
  [doc out-path & {:as opts}]
  (apply render! (model/emit doc) out-path (mapcat identity opts)))
