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
  (:require [hive-kdenlive.mlt.xml :as xml]
            [clojure.string :as str]))

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
  "A playlist <entry> referencing producer `id`, optionally cut by :in/:out.
   :filters (filter nodes) are nested inside the entry, so they act on this
   cut only. A nested filter's keyframes count from ITS OWN in, and its in/out
   are source frames like the entry's: give it in/out or its keyframes are
   read as source positions (measured 2026-09-15, melt 7.22)."
  [id & {:keys [in out filters] :as _opts}]
  (apply xml/element "entry" [["producer" id] ["in" in] ["out" out]] (vec filters)))

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

(defn- gcd [a b] (if (zero? b) a (recur b (mod a b))))

(defn aspect
  "[w h] reduced: the display aspect of square pixels at WIDTH x HEIGHT.
   A vertical 1080x1920 profile is 9:16; the profile default of 16:9 would
   tell a player to stretch it."
  [width height]
  (let [g (gcd width height)] [(quot width g) (quot height g)]))

;; ---------------------------------------------------------------------------
;; Titles — Kdenlive's own title format, rendered by the kdenlivetitle producer

(def ^:private qt5-weights
  "CSS weight -> the QFont weight Kdenlive 23 (Qt5) stores in a title. Qt5's
   scale is 0..99 and bold is 75: a title saying font-weight=\"700\" renders
   REGULAR (measured 2026-09-15). Callers speak CSS; the title speaks Qt5."
  [[100 0] [200 12] [300 25] [400 50] [500 57] [600 63] [700 75] [800 81] [900 87]])

(defn qt-weight
  "The Qt5 weight for a CSS weight (100..900), rounding down to a named step."
  [css-weight]
  (let [w (or css-weight 400)]
    (or (second (last (take-while (fn [[c _]] (<= c w)) qt5-weights))) 0)))

(defn- hex->int [s]
  (reduce (fn [acc c] (+ (* acc 16) (or (str/index-of "0123456789abcdef" (str c)) 0)))
          0
          (str/lower-case s)))

(defn rgba
  "\"#rrggbb\" or \"#rrggbbaa\" (CSS order, alpha LAST) -> \"r,g,b,a\", the
   colour spelling of a Kdenlive title. Not MLT's #AARRGGBB, where alpha comes
   first and \"#ff0000ff\" is opaque blue."
  [colour]
  (let [hex (str/replace (str colour) "#" "")
        hex (if (= 6 (count hex)) (str hex "ff") hex)]
    (str/join "," (map (fn [i] (hex->int (subs hex i (+ i 2)))) [0 2 4 6]))))

(def ^:private qt-align {:left 1 :right 2 :center 4})

(defn title
  "A <kdenlivetitle> node: one text block on a transparent (or :background)
   canvas of WIDTH x HEIGHT, FRAMES long. `xml/emit` it for the xmldata of a
   kdenlivetitle producer.

   opts :text (newlines break lines) :font :size (pixels) :weight (CSS)
        :color :background (\"#rrggbb[aa]\") :align (:left :center :right)
        :x :y (top-left of the text box) :box-width
   Unset :y centres the block vertically, from the line count and 1.25 x size
   per line; unset :x and :box-width span the canvas, so :align decides."
  [& {:keys [width height frames text font size weight color background align x y box-width]
      :or {font "DejaVu Sans" size 96 weight 700 color "#ffffff" background "#00000000"
           align :center}}]
  (let [lines  (max 1 (count (str/split-lines (str text))))
        box-h  (int (* lines size 1.25))
        x      (or x 0)
        bw     (or box-width (- width (* 2 x)))
        y      (or y (quot (- height box-h) 2))
        view   (str "0,0," width "," height)]
    (xml/element "kdenlivetitle"
                 [["duration" (str frames)] ["LC_NUMERIC" "C"] ["width" (str width)]
                  ["height" (str height)] ["out" (str (dec frames))]]
                 (xml/element "item" [["type" "QGraphicsTextItem"] ["z-index" "0"]]
                              (xml/element "position" [["x" (str x)] ["y" (str y)]]
                                           (xml/element "transform" [] "1,0,0,0,1,0,0,0,1"))
                              (xml/element "content"
                                           [["font-color" (rgba color)] ["font" font]
                                            ["font-pixel-size" (str size)]
                                            ["font-weight" (str (qt-weight weight))]
                                            ["font-italic" "0"] ["font-underline" "0"]
                                            ["alignment" (str (get qt-align (keyword align) 4))]
                                            ["box-width" (str bw)] ["box-height" (str box-h)]]
                                           (str text)))
                 (xml/element "startviewport" [["rect" view]])
                 (xml/element "endviewport" [["rect" view]])
                 (xml/element "background" [["color" (rgba background)]]))))

(defn title-producer
  "A kdenlivetitle <producer> for title XML text, FRAMES long. The resource is
   empty, as in a Kdenlive project; the title lives in xmldata."
  [title-xml frames & {:keys [id]}]
  (producer "" :id id :in "0" :out (str (dec frames))
            :properties {"length" (str frames) "eof" "pause"
                         "mlt_service" "kdenlivetitle" "xmldata" title-xml}))

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
