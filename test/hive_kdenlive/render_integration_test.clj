(ns hive-kdenlive.render-integration-test
  "End-to-end against the real melt binary. Skips when melt is not on PATH;
   excluded from the unit suite via the :integration keyword."
  (:require [clojure.test :refer [deftest is]]
            [hive-kdenlive.mlt.model :as model]
            [hive-kdenlive.render :as render]))

(deftest ^:integration real-melt-render-test
  (if-let [bin (render/which "melt")]
    (let [out (str (System/getProperty "java.io.tmpdir")
                   "/hive-kdenlive-it-" (System/nanoTime) ".mp4")
          doc (model/document
               (model/profile :width 320 :height 240 :fps 25)
               (model/producer "color:red" :id "p0" :length "25")
               (model/playlist "pl0" [(model/entry "p0")])
               (model/tractor "tractor0" [(model/track "pl0")]))
          res (binding [render/*renderer* (render/melt-renderer bin)]
                (render/render-doc! doc out))]
      (is (:ok res) (str "melt failed: " res))
      (is (.exists (java.io.File. out)))
      (.delete (java.io.File. out)))
    (is true "melt not on PATH — skipped")))
