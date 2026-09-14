(ns hive-kdenlive.kdenlive.document
  "The :document transport: IKdenlive over a project file, with no Kdenlive.

   `http-kdenlive` sends a catalog route to the scripting fork. This adapter
   answers the same route ids against a timeline kept on disk as EDN
   (hive-kdenlive.mlt.timeline), and after every edit writes the MLT document
   melt renders beside it:

     edit.hkd.edn   the timeline, the source of truth
     edit.mlt       its MLT rendering, rewritten on every change

   :render/start renders that document with melt. Media is probed with
   `melt <file> -consumer xml`, the same engine the render uses, so a length
   the timeline trusts is a length the render will agree with.

   Routes with no meaning off a running application (playback, render jobs)
   answer :document/unsupported-route, naming the routes that are supported."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-kdenlive.kdenlive.client :as client]
            [hive-kdenlive.mlt.model :as model]
            [hive-kdenlive.mlt.timeline :as timeline]
            [hive-kdenlive.render :as render]
            [hive-kdenlive.mlt.xml :as xml]))

;; ---------------------------------------------------------------------------
;; Effects the pure timeline asks for

(defn producer-length
  "The `length` property of the first <producer> in melt's XML description of
   a file, as a long, or nil."
  [melt-xml]
  (let [{:keys [ok]} (xml/parse melt-xml)
        producer     (when ok (first (xml/children ok "producer")))
        length       (some (fn [p] (when (= "length" (xml/attr p "name")) (xml/text p)))
                           (when producer (xml/children producer "property")))]
    (when length (parse-long length))))

(defn melt-probe
  "A :probe for the timeline: path -> {:length frames} | {:error ...}, read
   from melt's own description of the file (`melt <file> -consumer xml`)."
  ([] (melt-probe (render/which "melt")))
  ([melt-bin]
   (fn [path]
     (cond
       (nil? melt-bin) {:error :media/melt-not-found}
       (not (.isFile (io/file path))) {:error :media/file-not-found :path path}
       :else
       (let [proc   (.start (ProcessBuilder. ^java.util.List [melt-bin "-quiet" path "-consumer" "xml"]))
             out    (future (slurp (.getInputStream proc)))
             _      (slurp (.getErrorStream proc))
             exit   (.waitFor proc)
             length (when (zero? exit) (producer-length @out))]
         (cond
           (not (zero? exit)) {:error :media/melt-failed :exit exit :path path}
           (and length (pos? length)) {:length length}
           :else {:error :media/no-length :path path}))))))

;; ---------------------------------------------------------------------------
;; Files

(defn mlt-path
  "The .mlt written beside PROJECT-PATH: edit.hkd.edn -> edit.mlt."
  [project-path]
  (str (str/replace (str project-path) #"(\.hkd)?\.edn$" "") ".mlt"))

(defn- read-state [path]
  (let [f (io/file path)]
    (if (.isFile f)
      (edn/read-string (slurp f))
      (timeline/new-project (str/replace (.getName f) #"(\.hkd)?\.edn$" "")))))

(defn- write-atomically! [path text]
  (let [f   (io/file path)
        tmp (io/file (str path ".tmp"))]
    (when-let [dir (.getParentFile f)] (.mkdirs dir))
    (spit tmp text)
    (java.nio.file.Files/move (.toPath tmp) (.toPath f)
                              (into-array java.nio.file.CopyOption
                                          [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                           java.nio.file.StandardCopyOption/ATOMIC_MOVE]))))

(defn- write-state! [path state]
  (write-atomically! path (binding [*print-namespace-maps* false] (pr-str state)))
  (write-atomically! (mlt-path path) (model/emit (timeline/->document state))))

;; ---------------------------------------------------------------------------
;; The adapter

(defn- keywordize [params]
  (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword (str k))) v])) params))

(defn- render-start [path state {:keys [outputFile]}]
  (cond
    (str/blank? (str outputFile)) {:error :render/output-required}
    (zero? (timeline/duration state)) {:error :render/empty-timeline}
    :else
    (let [{:keys [ok error] :as res} (render/render-doc! (timeline/->document state) (str outputFile))]
      (if error
        res
        {:ok {:success true :outputFile (:out ok) :frames (timeline/duration state) :project path}}))))

(defrecord DocumentKdenlive [path probe]
  client/IKdenlive
  (-call [_ route-id params]
    (let [params (keywordize (or params {}))
          state  (read-state path)]
      (if (= :render/start route-id)
        (render-start path state params)
        (let [{:keys [ok error] :as res} (timeline/apply-verb state route-id params {:probe probe})]
          (if error
            res
            (do (when-not (= state (:state ok)) (write-state! path (:state ok)))
                {:ok (:result ok)})))))))

(defn document-kdenlive
  "An IKdenlive over the timeline at PATH (created on its first edit). PROBE
   defaults to melt's."
  ([path] (document-kdenlive path (melt-probe)))
  ([path probe] (->DocumentKdenlive (str path) probe)))
