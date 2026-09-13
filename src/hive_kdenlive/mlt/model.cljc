(ns hive-kdenlive.mlt.model
  "MLT document model: pure builders that produce xml.cljc node trees.

   MLT's XML vocabulary, smallest useful subset:
     mlt        root; LC_NUMERIC=\"C\" so float serialization is locale-invariant
     profile    frame geometry; the document carries one
     producer   a clip source; resource lives in a property child
     playlist   a track: <entry producer=.../> and <blank length=.../> refs
     tractor    the timeline: <multitrack> of <track producer=.../> refs
     filter     an effect on a producer/playlist/tractor
     transition a compositor between two tracks (a_track/b_track)

   Builders return xml.cljc nodes; `document` assembles and `xml/emit`
   serializes. No IO, no host conditionals — core + string only."
  (:refer-clojure :exclude [filter])
  (:require [hive-kdenlive.mlt.xml :as xml]))

(def mlt-version
  "MLT version stamped on emitted documents."
  "7.30.0")

;; ---------------------------------------------------------------------------
;; Properties — MLT nests all parameters as <property name="...">text</property>

(defn property
  "A <property name=\"k\">v</property> node. Keyword keys lose the colon
   (`:rect` -> \"rect\"); v is stringified as-is."
  [k v]
  (xml/element "property" [["name" (if (keyword? k) (name k) (str k))]] (str v)))

(defn properties
  "Map (or seq of pairs) to property nodes, in map order."
  [m]
  (mapv (fn [[k v]] (property k v)) m))

;; ---------------------------------------------------------------------------
;; Producers and filters

(defn producer
  "A <producer> for `resource` (path/URL/color/...).
   opts: :id :in :out :length :ttl :properties (map of extra properties)."
  [resource & {:keys [id in out length ttl] :as opts}]
  (apply xml/element "producer"
         [["id" id] ["in" in] ["out" out] ["length" length] ["ttl" ttl]]
         (into [(property "resource" resource)] (properties (:properties opts)))))

(defn filter
  "A <filter> node. `mlt-service` names the effect (e.g. \"obscure\", \"volume\");
   `args` is a map of effect parameters, emitted as <property> children so any
   mlt-service parameter is reachable without per-effect code."
  [mlt-service args & {:keys [id in out] :as _opts}]
  (apply xml/element "filter"
         [["id" id] ["in" in] ["out" out]]
         (into [(property "mlt_service" mlt-service)] (properties args))))

;; ---------------------------------------------------------------------------
;; Playlists — tracks

(defn entry
  "A playlist <entry> referencing producer `id`, optionally cut by :in/:out."
  [id & {:keys [in out] :as _opts}]
  (xml/element "entry" [["producer" id] ["in" in] ["out" out]]))

(defn blank
  "A <blank length=\"n\"/> gap in a playlist."
  [length]
  (xml/element "blank" [["length" (str length)]]))

(defn playlist
  "A <playlist id=...> from a seq of entry/blank nodes."
  [id items]
  (apply xml/element "playlist" [["id" id]] (vec items)))

;; ---------------------------------------------------------------------------
;; Tractor — the timeline

(defn track
  "A <track producer=\"playlist-id\"/> reference inside a multitrack.
   opts: :hide (:audio or :video) mutes one leg of the track."
  [playlist-id & {:keys [hide] :as _opts}]
  (xml/element "track" [["producer" playlist-id] ["hide" (when hide (name hide))]]))

(defn transition
  "A <transition> compositing `b-track` over `a-track`.
   `mlt-service` e.g. \"mix\" (audio) or \"frei0r.cairoblend\" (video)."
  [mlt-service a-track b-track args & {:keys [in out] :as _opts}]
  (apply xml/element "transition"
         [["in" in] ["out" out]]
         (into [(property "mlt_service" mlt-service)
                (property "a_track" a-track)
                (property "b_track" b-track)]
               (properties args))))

(defn tractor
  "A <tractor> timeline over `tracks` (track nodes), with optional
   transitions and filters appended after the multitrack."
  [id tracks & {:keys [transitions filters] :as _opts}]
  (apply xml/element "tractor"
         [["id" id] ["title" "hive-kdenlive"]]
         (into [(apply xml/element "multitrack" [] (vec tracks))]
               (concat transitions filters))))

;; ---------------------------------------------------------------------------
;; Profile and document

(defn profile
  "A <profile> node. opts: :width :height :fps [num den] :sar :dar :colorspace.
   fps may be a number (converted to the nearest standard fraction) or a
   [num den] pair for exact rates like [30000 1001]."
  [& {:keys [width height fps sar dar colorspace progressive]
      :or {width 1920 height 1080 fps 25 sar [1 1] dar [16 9] progressive 1
           colorspace 709}
      :as _opts}]
  (let [[num den] (if (sequential? fps)
                    fps
                    ;; round-half-up without Math/round (portable): fps are positive
                    (let [n (int (+ (* fps 1001) 0.5))]
                      (if (zero? (mod n 1001)) [(/ n 1001) 1] [n 1001])))]
    (xml/element "profile"
                 [["width" (str width)] ["height" (str height)]
                  ["progressive" (str progressive)]
                  ["sample_aspect_num" (str (first sar))]
                  ["sample_aspect_den" (str (second sar))]
                  ["display_aspect_num" (str (first dar))]
                  ["display_aspect_den" (str (second dar))]
                  ["frame_rate_num" (str num)] ["frame_rate_den" (str den)]
                  ["colorspace" (str colorspace)]])))

(defn document
  "Assemble the root <mlt> node. `children` are profile, producers, playlists,
   tractor in emission order."
  [& children]
  (apply xml/element "mlt"
         [["LC_NUMERIC" "C"] ["version" mlt-version] ["title" "hive-kdenlive"]]
         (vec children)))

(defn emit
  "Serialize a document (or any node) to an MLT XML string."
  [node]
  (xml/emit node))

(defn parse
  "Parse an MLT XML string (kdenlive project file, melt output) into nodes."
  [s]
  (xml/parse s))
