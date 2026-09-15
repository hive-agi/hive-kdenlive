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
  "Consumer arguments for OUT-PATH by extension: H.264 in yuv420p with AAC for
   .mp4/.mov/.m4v, the combination phones and social players accept, and
   faststart so playback begins before the download ends. Other extensions
   get melt's own choice."
  [out-path]
  (if (re-find #"(?i)\.(mp4|mov|m4v)$" (str out-path))
    ["vcodec=libx264" "pix_fmt=yuv420p" "crf=18" "preset=medium"
     "acodec=aac" "ab=192k" "movflags=+faststart"]
    []))

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
