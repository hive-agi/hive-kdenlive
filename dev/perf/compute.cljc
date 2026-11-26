;; Steady-state compute: sum 0..n-1 in a loop/recur, timed IN-PROCESS so the
;; number excludes launch and runtime init. One untimed warm pass, then
;; `trials` timed passes. Output is a fixed-shape line the harness parses:
;;   result=<sum> n=<n> trials=<t> ns_per_iter=<median> min=<min> max=<max>
;; Args: [n trials], defaults 100000 5. Expected result for n: n*(n-1)/2.
(defn arg [i dflt]
  (if-let [s (nth *command-line-args* i nil)] (parse-long s) dflt))

(defn sum-to [n]
  (loop [i 0 acc 0]
    (if (< i n) (recur (inc i) (+ acc i)) acc)))

(defn ns-per-iter [n]
  (let [t0 (System/nanoTime)
        r (sum-to n)
        dt (- (System/nanoTime) t0)]
    [r (quot dt (max n 1))]))

(let [n (arg 0 100000)
      trials (arg 1 5)
      _ (sum-to n)
      samples (vec (repeatedly trials #(ns-per-iter n)))
      result (ffirst samples)
      ts (vec (sort (map second samples)))]
  (println (str "result=" result " n=" n " trials=" trials
                " ns_per_iter=" (nth ts (quot (count ts) 2))
                " min=" (first ts) " max=" (peek ts))))
