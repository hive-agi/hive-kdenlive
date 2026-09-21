(ns hive-kdenlive.mlt.transitions
  "Transitions between two clips, as a PLAN of verbs the route catalog already
   names.

   Two clips on different video tracks overlap in time; the UPPER one is
   animated over the overlap and the audio is crossed. `plan` answers the
   calls that do it:

     {:ok {:overlap [start end) :upper id :calls [[route-id params] ...]}}

   Feed each call to `timeline/apply-verb` (the :document transport) or to the
   HTTP client: the same calls mean the same thing in both, and the result
   stays editable in the .kdenlive project as ordinary keyframes and fades.

   Kinds
     :dissolve                      the upper clip's opacity crosses 0..1
     :slide-left :slide-right       the incoming picture pushes in from a side
     :slide-up   :slide-down

   Which clip is animated follows from the tracks, never from a flag: a later
   track renders on top. When the INCOMING clip is on top it fades or slides
   in; when the OUTGOING clip is on top it fades or slides out, uncovering the
   incoming one under it.

   Refused by name rather than approximated: the clips do not overlap, they
   share a track, the incoming clip does not start inside the outgoing one,
   either is on an audio track, or the upper clip already carries transform
   keyframes a transition would overwrite.

   Portable: clojure.core only. No `for`."
  (:require [hive-kdenlive.mlt.timeline :as tl]))

(def kinds #{:dissolve :slide-left :slide-right :slide-up :slide-down})

(defn- located
  "{:clip :track-index :audio?} of timeline clip ID, or nil."
  [state id]
  (first (keep-indexed
          (fn [ti t]
            (when-let [c (first (filter (fn [c] (= (str id) (:id c))) (:clips t)))]
              {:clip c :track-index ti :audio? (= true (:audio? t))}))
          (:tracks state))))

(defn- rect
  "A full-frame keyframe at FRAME, moved by [dx dy], at OPACITY."
  [id frame W H dx dy opacity]
  [:clip/transform-keyframe
   {:clipId id :frame frame :x dx :y dy :width W :height H :opacity opacity}])

(defn- offset
  "Where the picture sits when it is fully OUT of frame for KIND."
  [kind W H]
  (get {:dissolve    [0 0]
        :slide-left  [W 0]
        :slide-right [(- W) 0]
        :slide-up    [0 H]
        :slide-down  [0 (- H)]}
       kind))

(defn plan
  "The verb calls that make KIND happen between timeline clips FROM (outgoing)
   and TO (incoming). => {:ok {...}} | {:error ...}."
  [state {:keys [kind from to] :or {kind :dissolve}}]
  (let [a (located state from)
        b (located state to)
        W (get-in state [:profile :width])
        H (get-in state [:profile :height])]
    (cond
      (not (contains? kinds kind)) {:error :transition/unknown-kind :kind kind :known (vec (sort kinds))}
      (nil? a) {:error :timeline/no-such-clip :id from}
      (nil? b) {:error :timeline/no-such-clip :id to}
      (or (:audio? a) (:audio? b)) {:error :transition/audio-track :from from :to to}
      (= (:track-index a) (:track-index b)) {:error :transition/same-track :track-index (:track-index a)}
      :else
      (let [ca    (:clip a)
            cb    (:clip b)
            start (:position cb)
            end   (min (tl/clip-end ca) (tl/clip-end cb))
            n     (- end start)]
        (cond
          (not (and (< (:position ca) start) (< start (tl/clip-end ca))))
          {:error :transition/incoming-must-start-inside-outgoing
           :outgoing [(:position ca) (tl/clip-end ca)] :incoming-starts start}

          (< n 2)
          {:error :transition/no-overlap :overlap [start end]}

          :else
          (let [in?     (< (:track-index a) (:track-index b))
                upper   (if in? cb ca)
                uid     (:id upper)
                [dx dy] (offset kind W H)
                fade?   (= :dissolve kind)
                ;; the incoming clip animates over its first n frames; the
                ;; outgoing one over its last n, leaving the way the other came
                f0      (if in? 0 (- (tl/clip-length ca) n))
                f1      (+ f0 (dec n))
                out-dx  (if in? dx (- dx))
                out-dy  (if in? dy (- dy))
                hidden  (rect uid (if in? f0 f1) W H out-dx out-dy (if fade? 0 1))
                shown   (rect uid (if in? f1 f0) W H 0 0 1)]
            (if (seq (:transform upper))
              {:error :transition/clip-has-transform :id uid}
              {:ok {:kind    kind
                    :overlap [start end]
                    :upper   uid
                    :calls   [(if in? hidden shown)
                              (if in? shown hidden)
                              [:clip/audio-fade {:clipId (:id ca)
                                                 :fadeIn  (get-in ca [:audio-fade :in] 0)
                                                 :fadeOut n}]
                              [:clip/audio-fade {:clipId (:id cb)
                                                 :fadeIn  n
                                                 :fadeOut (get-in cb [:audio-fade :out] 0)}]]}})))))))

(defn apply-plan
  "STATE after every call of a `plan` result, through the :document transport.
   => {:ok state'} | the first {:error ...}."
  [state calls ctx]
  (reduce (fn [acc [route params]]
            (if (:error acc)
              acc
              (let [res (tl/apply-verb (:ok acc) route params ctx)]
                (if (:error res) res {:ok (get-in res [:ok :state])}))))
          {:ok state}
          calls))
