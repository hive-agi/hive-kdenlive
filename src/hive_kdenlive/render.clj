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

(defn melt-argv
  "The melt command vector for rendering `mlt-path` to `out-path`.
   opts: :profile (avformat profile name), :extra (seq of raw args)."
  [melt-bin mlt-path out-path & {:keys [profile extra] :as _opts}]
  (into (cond-> [melt-bin mlt-path]
          profile (conj (str "profile=" profile)))
        (concat ["-consumer" (str "avformat:" out-path)] extra)))

(defn which
  "Absolute path of `bin` on PATH, or nil."
  [bin]
  (let [path (System/getenv "PATH")]
    (some (fn [dir]
            (let [f (io/file dir bin)]
              (when (.canExecute f) (.getAbsolutePath f))))
          (str/split (or path "") (re-pattern java.io.File/pathSeparator)))))

;; ---------------------------------------------------------------------------
;; Port

(defprotocol IRender
  (-render [renderer mlt-path out-path opts]
    "Render the MLT document at mlt-path to out-path.
     Returns {:ok {:out path :argv [...]}} | {:error :render/... :stderr string}"))

;; ---------------------------------------------------------------------------
;; Boundary

(defn- run-process
  [argv]
  (let [proc    (.start (ProcessBuilder. ^java.util.List argv))
        out     (slurp (.getInputStream proc))
        err     (slurp (.getErrorStream proc))
        exit    (.waitFor proc)]
    {:exit exit :stdout out :stderr err}))

(defrecord MeltRenderer [bin]
  IRender
  (-render [_ mlt-path out-path opts]
    (if-not bin
      {:error :render/melt-not-found :message "melt is not on PATH"}
      (let [argv (apply melt-argv bin mlt-path out-path (mapcat identity opts))
            {:keys [exit stderr]} (run-process argv)]
        (if (zero? exit)
          {:ok {:out out-path :argv argv}}
          {:error :render/melt-failed :exit exit :stderr stderr})))))

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
   written to a temp file because melt consumes paths, not stdin."
  [mlt-xml out-path & {:as opts}]
  (let [tmp (java.io.File/createTempFile "hive-kdenlive-" ".mlt")]
    (try
      (spit tmp mlt-xml)
      (-render (if (delay? *renderer*) @*renderer* *renderer*)
               (.getAbsolutePath tmp) out-path opts)
      (finally (.delete tmp)))))

(defn render-doc!
  "Like render! but takes a model node tree."
  [doc out-path & {:as opts}]
  (apply render! (model/emit doc) out-path (mapcat identity opts)))
