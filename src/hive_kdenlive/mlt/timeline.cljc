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

(defn- fps-ratio
  "[num den] for a profile :fps, which is a number or a [num den] pair."
  [fps]
  (if (sequential? fps) [(first fps) (second fps)] [fps 1]))

(defn at-project-rate
  "LENGTH frames counted at SOURCE-FPS ([num den], or nil for already at the
   project rate), as whole frames at PROJECT-FPS. Rounds down: a clip never
   claims a frame its source does not have.

   A probe counts at the rate melt chose for the file, 25 fps for audio and
   stills, and a 3 s WAV in a 30 fps project is 90 frames, not 75 (measured
   2026-09-15)."
  [length source-fps project-fps]
  (if (nil? source-fps)
    length
    (let [[sn sd] (fps-ratio source-fps)
          [pn pd] (fps-ratio project-fps)]
      (quot (* length sd pn) (* sn pd)))))

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
      (let [fps         (get-in state [:profile :fps])
            [state ids] (reduce (fn [[s ids] [path {:keys [length] :as r}]]
                                  (let [[id s] (fresh-id s)]
                                    [(assoc-in s [:bin id] {:id id :resource path :name (basename path)
                                                            :length (at-project-rate length (:fps r) fps)})
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
;; Profile and titles

(defn- project-profile
  "Frame size and rate. Titles already in the bin keep the size they were
   made at."
  [state {:keys [width height fpsNum fpsDen]} _]
  (let [w (->long width) h (->long height) n (->long fpsNum) d (->long fpsDen)]
    (if (and (pos-int? w) (pos-int? h) (pos-int? n) (pos-int? d))
      (ok (assoc state :profile {:width w :height h :fps (if (= 1 d) n [n d])}) {:success true})
      (err :project/bad-profile :width width :height height :fpsNum fpsNum :fpsDen fpsDen))))

(defn- present
  "M without its nil values: keyword args with a nil value would override a
   builder's :or defaults."
  [m]
  (reduce-kv (fn [acc k v] (if (nil? v) acc (assoc acc k v))) {} m))

(defn- media-create-title
  "A title clip in the bin, `duration` frames. `xml` is Kdenlive title XML,
   which is what the fork takes. With no xml, a text spec (text font size
   weight color background align x y boxWidth) builds that XML at the
   project's frame size, so both spellings land as the same producer."
  [state {:keys [xml duration name text font size weight color background align x y boxWidth]} _]
  (let [frames (->long duration)
        {:keys [width height]} (:profile state)
        xml    (cond
                 (not (str/blank? (str (or xml "")))) (str xml)
                 (not (str/blank? (str (or text "")))) (model/emit
                                                        (apply model/title
                                                               (mapcat identity
                                                                       (present {:width width :height height :frames frames
                                                                                 :text (str text) :font font :size (->long size)
                                                                                 :weight (->long weight) :color color
                                                                                 :background background
                                                                                 :align (when align (keyword (str align)))
                                                                                 :x (->long x) :y (->long y)
                                                                                 :box-width (->long boxWidth)}))))
                 :else nil)]
    (cond
      (not (pos-int? frames)) (err :title/bad-duration :duration duration)
      (nil? xml) (err :title/xml-or-text-required)
      :else (let [[id state] (fresh-id state)]
              (ok (assoc-in state [:bin id] {:id id :kind :title :name (str (or name "Title clip"))
                                             :xml xml :length frames})
                  {:id id})))))

;; ---------------------------------------------------------------------------
;; Clip properties and effects
;;
;; A clip carries what the fork sets on a timeline clip, as data:
;;   :effects    [{:id :service :in :out :params}]  in/out relative to the clip
;;   :transform  [{:frame :x :y :width :height :opacity}]  sorted by frame
;;   :opacity    number, when there are no transform keyframes
;;   :volume-db  number
;;   :audio-fade {:in frames :out frames}
;; ->document turns them into filters nested in the clip's playlist entry.

(defn- find-clip
  "[track-index clip-index] of timeline clip ID, or nil."
  [state id]
  (first (keep-indexed (fn [ti t]
                         (when-let [ci (first (keep-indexed (fn [ci c] (when (= (str id) (:id c)) ci))
                                                            (:clips t)))]
                           [ti ci]))
                       (:tracks state))))

(defn- update-clip
  "STATE with (f clip) at [ti ci]. update-in, never assoc-in: see `place`."
  [state [ti ci] f]
  (update-in state [:tracks ti] (fn [t] (assoc t :clips (assoc (:clips t) ci (f (get (:clips t) ci)))))))

(defn- with-clip
  "Call (f clip at) for the clip named by clipId or id, or refuse."
  [state {:keys [id clipId]} f]
  (let [cid (or clipId id)
        at  (find-clip state cid)]
    (if at
      (f (get-in state [:tracks (first at) :clips (second at)]) at)
      (err :timeline/no-such-clip :id cid))))

(defn- ->number [v]
  (cond (number? v) v
        (string? v) (parse-double v)
        :else nil))

(defn- param
  "V at K in a nested params map whose keys may be keywords or strings (JSON)."
  [m k]
  (let [v (get m k)] (if (nil? v) (get m (name k)) v)))

(defn- truthy? [v] (or (= true v) (= "true" v) (= 1 v) (= "1" v)))

(defn effect-spec
  "The filter EFFECT-ID puts on CLIP: {:ok {:service :in :out :params}} with
   in/out relative to the clip, or an error.

   Kdenlive's own effect ids keep Kdenlive's meaning (data/effects/*.xml):
     fade_from_black / fade_to_black   brightness over the first/last
       `duration` frames; with `alpha` the picture fades to transparent
       instead of to black, which is what an overlay wants
     fadein / fadeout                  volume, gain 0->1 / 1->0
   Any other id is an MLT service applied to the whole clip, params as given."
  [clip effect-id params]
  (let [len    (clip-length clip)
        d      (or (->long (param params :duration)) (min 25 len))
        alpha? (truthy? (param params :alpha))
        ramp   (fn [from to] (str "0=" from ";-1=" to))
        head   (fn [service ps] {:ok {:service service :in 0 :out (dec d) :params ps}})
        tail   (fn [service ps] {:ok {:service service :in (- len d) :out (dec len) :params ps}})
        fade?  (contains? #{"fade_from_black" "fade_to_black" "fadein" "fadeout"} effect-id)]
    (cond
      (and fade? (not (<= 1 d len))) (err :effect/bad-duration :duration d :clip-length len)
      (= "fade_from_black" effect-id) (head "brightness" (if alpha? {"level" "1" "alpha" (ramp 0 1)} {"level" (ramp 0 1) "alpha" "1"}))
      (= "fade_to_black" effect-id)   (tail "brightness" (if alpha? {"level" "1" "alpha" (ramp 1 0)} {"level" (ramp 1 0) "alpha" "1"}))
      (= "fadein" effect-id)          (head "volume" {"gain" "0" "end" "1"})
      (= "fadeout" effect-id)         (tail "volume" {"gain" "1" "end" "0"})
      :else {:ok {:service effect-id :in 0 :out (dec len)
                  :params (reduce-kv (fn [m k v] (assoc m (if (keyword? k) (name k) (str k)) (str v)))
                                     {} (or params {}))}})))

(defn- append-effect [state {:keys [effectId params] :as p} _]
  (if (str/blank? (str (or effectId "")))
    (err :effect/id-required)
    (with-clip state p
      (fn [clip at]
        (let [{spec :ok :as res} (effect-spec clip (str effectId) params)]
          (if-not spec
            res
            (let [[eid state] (fresh-id state)]
              (ok (update-clip state at (fn [c] (assoc c :effects (conj (vec (:effects c)) (assoc spec :id eid)))))
                  {:success true :id eid}))))))))

(defn- clip-opacity [state {:keys [opacity] :as p} _]
  (let [o (->number opacity)]
    (if-not (and o (<= 0 o 1))
      (err :clip/bad-opacity :opacity opacity)
      (with-clip state p (fn [_ at] (ok (update-clip state at (fn [c] (assoc c :opacity o))) {:success true}))))))

(defn- clip-volume [state {:keys [dB] :as p} _]
  (if-let [db (->number dB)]
    (with-clip state p (fn [_ at] (ok (update-clip state at (fn [c] (assoc c :volume-db db))) {:success true})))
    (err :clip/bad-volume :dB dB)))

(defn- audio-fade [state {:keys [fadeIn fadeOut] :as p} _]
  (let [fi (->long fadeIn) fo (->long fadeOut)]
    (with-clip state p
      (fn [clip at]
        (if-not (and fi fo (<= 0 fi) (<= 0 fo) (<= (+ fi fo) (clip-length clip)))
          (err :clip/bad-fade :fadeIn fadeIn :fadeOut fadeOut :clip-length (clip-length clip))
          (ok (update-clip state at (fn [c] (assoc c :audio-fade {:in fi :out fo}))) {:success true}))))))

(defn- transform-keyframe
  "A position/size/opacity keyframe at FRAME, counted from the clip's first
   frame. A keyframe at a frame that already has one replaces it."
  [state {:keys [frame x y width height opacity] :as p} _]
  (let [f  (->long frame)
        kf {:frame f :x (->long x) :y (->long y) :width (->long width) :height (->long height)
            :opacity (if (nil? opacity) 1 (->number opacity))}]
    (with-clip state p
      (fn [clip at]
        (cond
          (not (and f (<= 0 f) (< f (clip-length clip)))) (err :clip/bad-keyframe-frame :frame frame :clip-length (clip-length clip))
          (some nil? (vals kf)) (err :clip/bad-keyframe :keyframe p)
          (not (<= 0 (:opacity kf) 1)) (err :clip/bad-opacity :opacity opacity)
          :else (ok (update-clip state at
                                 (fn [c] (assoc c :transform
                                                (vec (sort-by :frame (conj (vec (remove (fn [k] (= f (:frame k))) (:transform c))) kf))))))
                    {:success true}))))))

;; ---------------------------------------------------------------------------
;; The verb table

(def verbs
  "Catalog route id -> verb. The ids are hive-kdenlive.kdenlive.routes's, so a
   caller holding the fork's vocabulary reaches the same operation here."
  {:project/new                project-new
   :project/info               project-info
   :project/profile            project-profile
   :media/import               media-import
   :media/list                 media-list
   :media/create-title         media-create-title
   :timeline/tracks            tracks
   :timeline/add-track         add-track
   :timeline/delete-track      delete-track
   :timeline/track-clips       track-clips
   :timeline/insert-clip       insert-clip
   :timeline/insert-clips-batch insert-clips-batch
   :timeline/insert-space      insert-space
   :timeline/zone-extract      zone-extract
   :clip/append-effect         append-effect
   :clip/opacity               clip-opacity
   :clip/volume                clip-volume
   :clip/audio-fade            audio-fade
   :clip/transform-keyframe    transform-keyframe})

(defn apply-verb
  "Answer route ROUTE-ID against STATE. {:ok {:state :result}} | {:error ...}."
  [state route-id params ctx]
  (if-let [verb (get verbs route-id)]
    (verb state (or params {}) (or ctx {}))
    (err :document/unsupported-route :route route-id
         :supported (vec (sort (map (fn [k] (str (namespace k) "/" (name k))) (keys verbs)))))))

;; ---------------------------------------------------------------------------
;; To MLT

(defn- clip-filters
  "The filters nested in CLIP's playlist entry. Each carries in/out in source
   frames (the clip's :in plus its clip-relative offset), so its keyframes
   count from its own first frame; without in/out MLT reads them as source
   positions and a fade on a trimmed clip never happens (measured)."
  [state clip]
  (let [W     (get-in state [:profile :width])
        H     (get-in state [:profile :height])
        len   (clip-length clip)
        at    (fn [rel] (str (+ (:in clip) rel)))
        span  (fn [service params from to] (model/filter service params :in (at from) :out (at to)))
        fades (:audio-fade clip)
        kfs   (:transform clip)
        rect  (fn [k] (str (:x k) " " (:y k) " " (:width k) " " (:height k) " " (:opacity k)))
        blend (fn [r] (span "qtblend" {"rect" r "compositing" "0" "distort" "0"} 0 (dec len)))]
    (vec (concat
          (map (fn [e] (span (:service e) (:params e) (:in e) (:out e))) (:effects clip))
          (when (pos? (or (:in fades) 0))
            [(span "volume" {"gain" "0" "end" "1"} 0 (dec (:in fades)))])
          (when (pos? (or (:out fades) 0))
            [(span "volume" {"gain" "1" "end" "0"} (- len (:out fades)) (dec len))])
          (when (some? (:volume-db clip))
            [(span "volume" {"level" (str (:volume-db clip))} 0 (dec len))])
          (cond
            (seq kfs) [(blend (str/join ";" (map (fn [k] (str (:frame k) "=" (rect k))) kfs)))]
            (some? (:opacity clip)) [(blend (str "0 0 " W " " H " " (:opacity clip)))]
            :else nil)))))

(defn- playlist-items
  "Entries and blanks for sorted CLIPS, the gaps between them as blanks."
  [state clips]
  (loop [cursor 0, items [], clips (seq clips)]
    (if-not clips
      items
      (let [c   (first clips)
            gap (- (:position c) cursor)
            items (if (pos? gap) (conj items (model/blank gap)) items)]
        (recur (clip-end c)
               (conj items (model/entry (str "bin" (:bin c)) :in (str (:in c)) :out (str (:out c))
                                        :filters (clip-filters state c)))
               (next clips))))))

(defn ->document
  "The MLT document melt renders for STATE.

   Details that are load-bearing, each measured by sampling rendered pixels
   or audio levels:
   - a background track (black, the whole duration) sits under every other
     track, because a blank with nothing below it renders white; the black
     producer's resource is the bare colour `black` under mlt_service=color.
   - every video track is composited onto the tracks below by a qtblend
     transition, as Kdenlive's internal ones do. Without it the top track
     REPLACES what is under it, alpha and all: a transparent overlay renders
     on black.
   - every track is summed into the mix by a `mix` transition. Without it only
     the top track's audio is heard.
   - the profile's display aspect is the frame's own, so 1080x1920 is 9:16."
  [state]
  (let [{:keys [width height fps]} (:profile state)
        total    (max 1 (duration state))
        producers (mapv (fn [{:keys [id resource length kind xml]}]
                          (if (= :title kind)
                            (model/title-producer xml length :id (str "bin" id))
                            (model/producer resource :id (str "bin" id) :in "0" :out (str (dec length)))))
                        (sort-by :id (vals (:bin state))))
        black    (model/producer "black" :id "black_track" :in "0" :out (str (dec total))
                                 :properties {"mlt_service" "color" "length" (str total)})
        lists    (mapv (fn [t] (model/playlist (str "track" (:id t)) (playlist-items state (:clips t))))
                       (:tracks state))
        tracks   (into [(model/track "background")]
                       (map (fn [t] (model/track (str "track" (:id t))
                                                 :hide (when (= true (:audio? t)) :video)))
                            (:tracks state)))
        video    (keep-indexed (fn [i t]
                                 (when-not (= true (:audio? t))
                                   (model/transition "qtblend" "0" (str (inc i))
                                                     {"compositing" "0" "distort" "0" "rotate_center" "0"
                                                      "always_active" "1" "internal_added" "237"})))
                               (:tracks state))
        audio    (map-indexed (fn [i _]
                                (model/transition "mix" "0" (str (inc i))
                                                  {"always_active" "1" "sum" "1" "internal_added" "237"}))
                              (:tracks state))]
    (apply model/document
           (concat [(model/profile :width width :height height :fps fps :dar (model/aspect width height))
                    black]
                   producers
                   [(model/playlist "background" [(model/entry "black_track" :in "0" :out (str (dec total)))])]
                   lists
                   [(model/tractor "main" tracks :transitions (vec (concat video audio)))]))))
