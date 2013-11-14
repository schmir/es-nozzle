(ns brainbot.nozzle.flowcontrol
  (:require [overtone.at-at :refer [after every mk-pool]])
  (:require [brainbot.nozzle.manage :as manage]
            [brainbot.nozzle.routing-key :as rk]))

(def my-pool (mk-pool))

(def ^{:doc "transitive message flow table"}
  msg-flow-list
  [{:dest ["import_file"]
    :sources ["extract_content" "update_directory" "get_permissions" "listdir"]}
   {:dest ["import_file" "extract_content"]
    :sources ["update_directory" "get_permissions" "listdir"]}
   {:dest ["import_file" "extract_content" "update_directory"]
    :sources ["get_permissions" "listdir"]}])

(defn- make-valid-names
  [id filesystem commands]
  (set (map #(rk/routing-key-string id filesystem %) commands)))




(defn examine-queue-state
  [queue-state]
  (println "current queue-state" queue-state))

