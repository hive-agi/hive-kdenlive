(ns hive-kdenlive.mlt.timeline
  "A Kdenlive timeline as data, edited by the SAME verbs the scripting fork
   answers over HTTP, with no Kdenlive running.

   The fork's route catalog (hive-kdenlive.kdenlive.routes) names what an agent
   can ask of a timeline: import media, add a track, insert a clip at a frame,
   open space, extract a zone. `apply-verb` answers those route ids against a
   value instead of a live application, and `->document` turns the value into
   an MLT document melt renders. That is the :document transport: the same
   vocabulary, a different place for the timeline to live.

   State
     {:name    \"edit\"
      :profile {:width 1920 :height 1080 :fps 25}
      :next-id 1
      :bin     {\"1\" {:id \"1\" :resource \"/clips/a.mkv\" :name \"a.mkv\" :length 250}}
      :tracks  [{:id \"2\" :name \"V1\" :audio? false
                 :clips [{:id \"3\" :bin \"1\" :position 0 :in 0 :out 249}]}]}

   Frames are integers throughout; :out is inclusive, as in MLT. Clips on a
   track never overlap: a verb that would make them overlap, or that would have
   to cut a clip in two, is REFUSED with a named error rather than approximated.
   Ids are strings, as the fork returns them.

   Every verb is (fn [state params ctx] -> {:ok {:state s :result r}} | {:error ...}).
   `ctx` carries effects the pure core cannot perform: :probe, a function from
   a media path to {:length frames} or {:error ...}.

   Portable: clojure.core and clojure.string only. No `for` (clojurust
   mishandles :when/:let in it), booleans compared with `=`."
  (:require [clojure.string :as str]
            [hive-kdenlive.mlt.model :as model]))

;; ---------------------------------------------------------------------------
;; State

(defn new-project
  ([] (new-project "untitled"))
  ([name]
   {:name name :profile {:width 1920 :height 1080 :fps 25} :next-id 1 :bin {} :tracks []}))

(defn- fresh-id [state] [(str (:next-id state)) (update state :next-id inc)])

(defn- ok [state result] {:ok {:state state :result result}})

(defn- err [code & {:as detail}] (merge {:error code} detail))

(defn clip-length [clip] (inc (- (:out clip) (:in clip))))

(defn clip-end
  "The first frame AFTER clip on its track."
  [clip]
  (+ (:position clip) (clip-length clip)))

(defn duration
  "Frames from 0 to the end of the last clip on any track."
  [state]
  (reduce max 0 (map clip-end (mapcat :clips (:tracks state)))))

(defn- track-index
  [state id]
  (first (keep-indexed (fn [i t] (when (= (str id) (:id t)) i)) (:tracks state))))

(defn- ->long [v]
  (cond (integer? v) v
        (number? v) (long v)
        (string? v) (parse-long v)
        :else nil))

(defn- sort-clips [clips] (vec (sort-by :position clips)))

(defn- overlapping
  "Clips in CLIPS that share a frame with [start, end)."
  [clips start end]
  (filterv (fn [c] (and (< (:position c) end) (< start (clip-end c)))) clips))

;; ---------------------------------------------------------------------------
;; Reads

(defn- project-info [state _ _]
  (ok state {:name     (:name state)
             :width    (get-in state [:profile :width])
             :height   (get-in state [:profile :height])
             :fps      (get-in state [:profile :fps])
             :duration (duration state)}))

(defn- media-list [state _ _]
  (ok state {:clips (vec (sort-by :id (vals (:bin state))))}))

(defn- tracks [state _ _]
  (ok state {:tracks (mapv (fn [t] {:id (:id t) :name (:name t) :audio (= true (:audio? t))
                                    :clips (count (:clips t))})
                           (:tracks state))}))

(defn- track-clips [state {:keys [id]} _]
  (if-let [i (track-index state id)]
    (ok state {:clips (mapv (fn [c] {:id (:id c) :binId (:bin c) :position (:position c)
                                     :in (:in c) :out (:out c) :duration (clip-length c)})
                            (get-in state [:tracks i :clips]))})
    (err :timeline/no-such-track :id id)))

;; ---------------------------------------------------------------------------
;; Project and media

(defn- project-new [_ {:keys [name]} _]
  (if (str/blank? (str name))
    (err :project/name-required)
    (ok (new-project (str name)) {:name (str name)})))

(defn- basename [path] (last (str/split (str path) #"/")))

(defn- media-import
  "Adds every path to the bin, or none of them: one unprobeable file refuses
   the whole import, so a caller never has to work out which half happened."
  [state {:keys [paths]} ctx]
  (let [paths  (if (string? paths) [paths] (vec paths))
        probe  (:probe ctx)
        probes (mapv (fn [p] [p (if probe (probe p) {:error :media/no-probe})]) paths)
        bad    (first (filter (fn [[_ r]] (or (:error r) (not (pos-int? (:length r))))) probes))]
    (cond
      (empty? paths) (err :media/paths-required)
      bad (err :media/unreadable :path (first bad) :detail (second bad))
      :else
      (let [[state ids] (reduce (fn [[s ids] [path {:keys [length]}]]
                                  (let [[id s] (fresh-id s)]
                                    [(assoc-in s [:bin id] {:id id :resource path :name (basename path)
                                                            :length length})
                                     (conj ids id)]))
                                [state []]
                                probes)]
        (ok state {:ids ids})))))

;; ---------------------------------------------------------------------------
;; Tracks

(defn- add-track [state {:keys [name isAudio]} _]
  (let [[id state] (fresh-id state)
        audio?     (or (= true isAudio) (= "true" isAudio))]
    (ok (update state :tracks conj {:id id :name (or name (str (if audio? "A" "V") id))
                                    :audio? audio? :clips []})
        {:id id})))

(defn- delete-track [state {:keys [id]} _]
  (if-let [i (track-index state id)]
    (ok (update state :tracks (fn [ts] (vec (concat (subvec ts 0 i) (subvec ts (inc i))))))
        {:success true})
    (err :timeline/no-such-track :id id)))

;; ---------------------------------------------------------------------------
;; Clips

(defn- place
  "STATE with bin clip BIN-ID placed on track I at POSITION, or an error."
  [state i bin-id position in out]
  (let [media (get-in state [:bin (str bin-id)])
        in    (or (->long in) 0)
        out   (or (->long out) (when media (dec (:length media))))
        pos   (->long position)]
    (cond
      (nil? media) (err :timeline/no-such-bin-clip :binId bin-id)
      (or (nil? pos) (neg? pos)) (err :timeline/bad-position :position position)
      (not (<= 0 in out (dec (:length media))))
      (err :timeline/bad-range :in in :out out :length (:length media))
      :else
      (let [[id state] (fresh-id state)
            clip       {:id id :bin (str bin-id) :position pos :in in :out out}
            clips      (get-in state [:tracks i :clips])
            clash      (overlapping clips pos (clip-end clip))]
        (if (seq clash)
          (err :timeline/overlap :position pos :end (clip-end clip) :clips (mapv :id clash))
          ;; update-in + assoc, not assoc-in: clojurust's assoc-in turns a vector
          ;; on the path into a map ({:tracks {0 ...}}), dropping every other track.
          {:ok [(update-in state [:tracks i] assoc :clips (sort-clips (conj clips clip))) id (clip-end clip)]})))))

(defn- insert-clip [state {:keys [binId trackId position in out]} _]
  (if-let [i (track-index state trackId)]
    (let [{:keys [ok error] :as res} (place state i binId position in out)]
      (if error res (let [[s id] ok] {:ok {:state s :result {:id id}}})))
    (err :timeline/no-such-track :id trackId)))

(defn- insert-clips-batch
  "Back to back from startPosition; all placed or none."
  [state {:keys [binIds trackId startPosition]} _]
  (if-let [i (track-index state trackId)]
    (loop [s state, pos (->long startPosition), ids [], bins (seq binIds)]
      (if-not bins
        (ok s {:ids ids})
        (let [{:keys [ok error] :as res} (place s i (first bins) pos nil nil)]
          (if error
            res
            (let [[s id end] ok] (recur s end (conj ids id) (next bins)))))))
    (err :timeline/no-such-track :id trackId)))

(defn- shift-from
  "Clips starting at or after FRAME move by DELTA; a clip spanning FRAME is
   returned as the error, since opening space inside it would cut it."
  [clips frame delta]
  (if-let [split (first (filter (fn [c] (and (< (:position c) frame) (< frame (clip-end c)))) clips))]
    {:error split}
    {:ok (sort-clips (map (fn [c] (if (<= frame (:position c)) (update c :position + delta) c)) clips))}))

(defn- insert-space [state {:keys [trackId position duration allTracks]} _]
  (let [frame (->long position)
        delta (->long duration)
        all?  (or (= true allTracks) (= "true" allTracks))
        idxs  (if all? (range (count (:tracks state))) (keep identity [(track-index state trackId)]))]
    (cond
      (or (nil? frame) (neg? frame)) (err :timeline/bad-position :position position)
      (not (pos-int? delta)) (err :timeline/bad-duration :duration duration)
      (and (not all?) (empty? idxs)) (err :timeline/no-such-track :id trackId)
      :else
      (let [res (reduce (fn [acc i]
                          (let [{:keys [ok error]} (shift-from (get-in acc [:tracks i :clips]) frame delta)]
                            (if error
                              (reduced (err :timeline/space-splits-clip :clip (:id error) :position frame))
                              (update-in acc [:tracks i] assoc :clips ok))))
                        state
                        idxs)]
        (if (:error res) res (ok res {:success true}))))))

(defn- zone-extract
  "Removes frames inFrame..outFrame on every track. liftOnly leaves the gap;
   otherwise later clips close it. A clip that crosses either edge of the zone
   refuses the whole extract: honouring it would cut the clip."
  [state {:keys [inFrame outFrame liftOnly]} _]
  (let [from  (->long inFrame)
        to    (->long outFrame)
        lift? (or (= true liftOnly) (= "true" liftOnly))]
    (if-not (and from to (<= 0 from to))
      (err :timeline/bad-zone :inFrame inFrame :outFrame outFrame)
      (let [end     (inc to)
            width   (- end from)
            crossed (first (filter (fn [c] (and (seq (overlapping [c] from end))
                                                (or (< (:position c) from) (< end (clip-end c)))))
                                   (mapcat :clips (:tracks state))))]
        (if crossed
          (err :timeline/zone-splits-clip :clip (:id crossed) :inFrame from :outFrame to)
          (ok (update state :tracks
                      (fn [ts]
                        (mapv (fn [t]
                                (update t :clips
                                        (fn [cs]
                                          (sort-clips
                                           (keep (fn [c]
                                                   (cond
                                                     (seq (overlapping [c] from end)) nil
                                                     (and (not lift?) (<= end (:position c))) (update c :position - width)
                                                     :else c))
                                                 cs)))))
                              ts)))
              {:success true}))))))

;; ---------------------------------------------------------------------------
;; The verb table

(def verbs
  "Catalog route id -> verb. The ids are hive-kdenlive.kdenlive.routes's, so a
   caller holding the fork's vocabulary reaches the same operation here."
  {:project/new                project-new
   :project/info               project-info
   :media/import               media-import
   :media/list                 media-list
   :timeline/tracks            tracks
   :timeline/add-track         add-track
   :timeline/delete-track      delete-track
   :timeline/track-clips       track-clips
   :timeline/insert-clip       insert-clip
   :timeline/insert-clips-batch insert-clips-batch
   :timeline/insert-space      insert-space
   :timeline/zone-extract      zone-extract})

(defn apply-verb
  "Answer route ROUTE-ID against STATE. {:ok {:state :result}} | {:error ...}."
  [state route-id params ctx]
  (if-let [verb (get verbs route-id)]
    (verb state (or params {}) (or ctx {}))
    (err :document/unsupported-route :route route-id
         :supported (vec (sort (map (fn [k] (str (namespace k) "/" (name k))) (keys verbs)))))))

;; ---------------------------------------------------------------------------
;; To MLT

(defn- playlist-items
  "Entries and blanks for sorted CLIPS, the gaps between them as blanks."
  [clips]
  (loop [cursor 0, items [], clips (seq clips)]
    (if-not clips
      items
      (let [c   (first clips)
            gap (- (:position c) cursor)
            items (if (pos? gap) (conj items (model/blank gap)) items)]
        (recur (clip-end c)
               (conj items (model/entry (str "bin" (:bin c)) :in (str (:in c)) :out (str (:out c))))
               (next clips))))))

(defn ->document
  "The MLT document melt renders for STATE.

   Two details are load-bearing, both measured by sampling rendered pixels:
   a background track (black, the whole duration) sits under every other
   track, because a blank with nothing below it renders white; and the black
   producer's resource is the bare colour `black` under mlt_service=color."
  [state]
  (let [{:keys [width height fps]} (:profile state)
        total    (max 1 (duration state))
        producers (mapv (fn [{:keys [id resource length]}]
                          (model/producer resource :id (str "bin" id) :in "0" :out (str (dec length))))
                        (sort-by :id (vals (:bin state))))
        black    (model/producer "black" :id "black_track" :in "0" :out (str (dec total))
                                 :properties {"mlt_service" "color" "length" (str total)})
        lists    (mapv (fn [t] (model/playlist (str "track" (:id t)) (playlist-items (:clips t))))
                       (:tracks state))
        tracks   (into [(model/track "background")]
                       (map (fn [t] (model/track (str "track" (:id t))
                                                 :hide (when (= true (:audio? t)) :video)))
                            (:tracks state)))]
    (apply model/document
           (concat [(model/profile :width width :height height :fps fps)
                    black]
                   producers
                   [(model/playlist "background" [(model/entry "black_track" :in "0" :out (str (dec total)))])]
                   lists
                   [(model/tractor "main" tracks)]))))
