(ns hive-kdenlive.mlt.project
  "Read an MLT / .kdenlive document back into a summary — the inverse leg of
   mlt.model. Pure and portable: nodes in, data out, no IO.

   A .kdenlive file is an MLT document with Kdenlive conventions: the bin
   lives in a playlist id=\"main_bin\", the timeline is the tractor whose
   multitrack references the track playlists, and cuts are in/out attributes
   (clock strings or frame counts)."
  (:require [hive-kdenlive.mlt.time :as time]
            [hive-kdenlive.mlt.xml :as xml]))

(defn- props
  "A node's <property> children as a map: name -> text."
  [node]
  (into {} (map (fn [p] [(xml/attr p "name") (xml/text p)]))
        (xml/children node "property")))

(defn profile-summary
  "The document's <profile> as data, numbers parsed."
  [mlt-node]
  (when-let [p (first (xml/children mlt-node "profile"))]
    {:width    (parse-long (xml/attr p "width"))
     :height   (parse-long (xml/attr p "height"))
     :fps      [(parse-long (xml/attr p "frame_rate_num"))
                (parse-long (xml/attr p "frame_rate_den"))]
     :progressive? (= "1" (xml/attr p "progressive"))}))

(defn bin
  "The project bin: clips referenced by the main_bin playlist, as
   [{:id :resource} ...]. Kdenlive writes bin clips as <chain> (modern) or
   <producer> (legacy) elements; both carry the resource in a property.
   A bare MLT document has no main_bin and its producers are the bin."
  [mlt-node]
  (let [main-bin  (first (filter #(= "main_bin" (xml/attr % "id"))
                                 (xml/children mlt-node "playlist")))
        clips     (concat (xml/children mlt-node "producer")
                          (xml/children mlt-node "chain"))
        by-id     (into {} (map (fn [p] [(xml/attr p "id") p])) clips)]
    (if main-bin
      (mapv (fn [e]
              (let [p (by-id (xml/attr e "producer"))]
                {:id       (xml/attr e "producer")
                 :resource (get (props p) "resource")}))
            (xml/children main-bin "entry"))
      (mapv (fn [p] {:id (xml/attr p "id") :resource (get (props p) "resource")})
            clips))))

(defn- entry-summary
  [e]
  {:producer (xml/attr e "producer")
   :in       (xml/attr e "in")
   :out      (xml/attr e "out")})

(defn- tractor-tracks
  "A tractor's <track> refs. melt writes them inside <multitrack>; Kdenlive
   writes them as DIRECT children alongside properties and filters. Both are
   the same tree to MLT — accept both."
  [tractor]
  (xml/children (or (first (xml/children tractor "multitrack")) tractor)
                "track"))

(defn tracks
  "The timeline's tracks as data. Real Kdenlive files NEST tractors: the
   timeline is a tractor whose tracks reference sub-tractors (one per
   timeline row, each pairing a video and an audio playlist) and producer0
   (black). Tractor order and position are not reliable — the timeline is
   the track-bearing tractor with the MOST track refs, ties broken last.

   Each timeline track resolves one level: {:id :tracks [...]}, where a
   track referencing a playlist expands to {:id :hide :entries :blanks}
   and one referencing a sub-tractor keeps :id with :tracks nested.

   :blanks are frame counts. Kdenlive writes a blank's length as a clock
   (\"00:00:00.640\"), resolved at FPS (default: the document's profile); a
   clock that cannot be resolved stays the string it was."
  ([mlt-node] (tracks mlt-node (:fps (profile-summary mlt-node))))
  ([mlt-node fps]
   (let [tractors  (xml/children mlt-node "tractor")
         by-id     (into {} (map (fn [t] [(xml/attr t "id") t])) tractors)
         playlists (into {} (map (fn [p] [(xml/attr p "id") p]))
                         (xml/children mlt-node "playlist"))
         frames    (fn [s] (or (when (re-matches #"\d+" (str s)) (parse-long s))
                               (when (and fps (string? s)) (time/clock->frames s fps))
                               s))
         expand    (fn expand [t]
                     (let [ref (xml/attr t "producer")]
                       (cond
                         (contains? playlists ref)
                         {:id      ref
                          :hide    (xml/attr t "hide")
                          :entries (mapv entry-summary
                                         (xml/children (playlists ref) "entry"))
                          :blanks  (mapv #(frames (xml/attr % "length"))
                                         (xml/children (playlists ref) "blank"))}

                         (contains? by-id ref)
                         {:id     ref
                          :tracks (mapv expand (tractor-tracks (by-id ref)))}

                         :else {:id ref})))
         timeline  (last (sort-by #(count (tractor-tracks %)) tractors))]
     (when timeline
       (mapv expand (tractor-tracks timeline))))))

(defn summarize
  "Parse an MLT XML string into {:profile :bin :tracks}.
   Returns {:error ...} unchanged on malformed input."
  [s]
  (let [{:keys [ok error] :as res} (xml/parse s)]
    (if error
      res
      {:ok {:profile (profile-summary ok)
            :bin     (bin ok)
            :tracks  (tracks ok)}})))

(defn- leaf-tracks
  "All playlist-level tracks under a track tree (sub-tractor nesting flattened)."
  [tracks]
  (mapcat (fn [t]
            (if (:tracks t) (leaf-tracks (:tracks t)) [t]))
          tracks))

(defn duration-frames
  "Total timeline length in frames: the longest track's entries + blanks,
   resolved at the profile's fps. Cuts given as clocks convert; bare numbers
   are already frames; a blank `tracks` could not resolve counts 0.
   Sub-tractor nesting is flattened first."
  [{:keys [profile tracks]}]
  (let [fps (:fps profile)]
    (when (and fps tracks)
      (reduce (fn [best t]
                (let [n (reduce (fn [acc item]
                                  (if (contains? item :producer)
                                    (let [{:keys [in out]} item]
                                      (if (and in out)
                                        (+ acc (or (time/duration in out fps) 0))
                                        acc))
                                    (+ acc (if (number? (:length item)) (:length item) 0))))
                                0
                                (concat (:entries t)
                                        (map (fn [b] {:length b}) (:blanks t))))]
                  (max best n)))
              0
              (leaf-tracks tracks)))))
