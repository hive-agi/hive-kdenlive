(ns hive-kdenlive.mlt.time
  "Time arithmetic for MLT in/out/length attributes.

   MLT accepts two time vocabularies: frame counts (\"150\") and clock strings
   (\"HH:MM:SS.mmm\"). Conversion between them needs the profile's fps, carried
   here as a [num den] fraction (a bare number means [n 1]).

   Portable: core + string only, integer math where hosts disagree on rounding."
  (:require [clojure.string :as str]))

(defn fps-pair
  "Normalize fps to [num den]: [30000 1001] stays, 25 -> [25 1], 29.97 rounds
   into x/1001 form."
  [fps]
  (cond
    (sequential? fps) fps
    (integer? fps)    [fps 1]
    :else             (let [n (int (+ (* fps 1001) 0.5))]
                        (if (zero? (mod n 1001)) [(/ n 1001) 1] [n 1001]))))

(defn- round-half-up
  "Positive doubles only; portable replacement for Math/round."
  [x]
  (int (+ x 0.5)))

(defn frames->millis
  "Milliseconds spanned by `n` frames at `fps`."
  [n fps]
  (let [[num den] (fps-pair fps)]
    (round-half-up (/ (* n 1000 den) num))))

(defn millis->frames
  "Frames spanned by `ms` milliseconds at `fps`."
  [ms fps]
  (let [[num den] (fps-pair fps)]
    (round-half-up (/ (* ms num) (* 1000 den)))))

(defn clock->millis
  "Parse \"HH:MM:SS.mmm\" (hours and millis optional: \"MM:SS\", \"SS.mmm\") to
   milliseconds. Returns nil for malformed input."
  [s]
  (when (string? s)
    (let [parts           (str/split s #":")
          n               (count parts)
          [h m sec-s]     (case n
                            1 ["0" "0" (first parts)]
                            2 ["0" (first parts) (second parts)]
                            3 parts
                            nil)
          [_ sec ms]      (when sec-s (re-matches #"(\d+)(?:\.(\d{1,3}))?" sec-s))
          ms              (when ms (str ms (apply str (repeat (- 3 (count ms)) \0))))]
      (when (and sec (every? #(re-matches #"\d+" %) [h m]))
        (+ (* (parse-long h) 3600000)
           (* (parse-long m) 60000)
           (* (parse-long sec) 1000)
           (if ms (parse-long ms) 0))))))

(defn- pad [n width]
  (let [s (str n)]
    (if (< (count s) width)
      (str (apply str (repeat (- width (count s)) \0)) s)
      s)))

(defn millis->clock
  "Format milliseconds as \"HH:MM:SS.mmm\"."
  [ms]
  (let [h   (quot ms 3600000)
        m   (quot (mod ms 3600000) 60000)
        s   (quot (mod ms 60000) 1000)
        mss (mod ms 1000)]
    (str (pad h 2) ":" (pad m 2) ":" (pad s 2) "." (pad mss 3))))

(defn clock->frames
  "Clock string to frame count at `fps`. A bare integer string is already a
   frame count and passes through. nil for a malformed string."
  [s fps]
  (if (re-matches #"\d+" s)
    (parse-long s)
    (when-let [ms (clock->millis s)] (millis->frames ms fps))))

(defn frames->clock
  "Frame count to \"HH:MM:SS.mmm\" at `fps`."
  [n fps]
  (millis->clock (frames->millis n fps)))

(defn duration
  "Frame length of the cut [in out] (inclusive, MLT-style). Ends may be frame
   counts or clock strings; both must resolve at `fps`."
  [in out fps]
  (let [i (clock->frames (str in) fps)
        o (clock->frames (str out) fps)]
    (when (and i o (<= i o))
      (inc (- o i)))))
