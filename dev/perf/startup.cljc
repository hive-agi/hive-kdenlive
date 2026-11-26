;; Startup probe: the smallest program that proves the runtime ran.
;; Wall time of this process is the per-launch floor (launch + runtime init +
;; core load + exit); every other workload's wall time includes it.
(println "startup-ok")
