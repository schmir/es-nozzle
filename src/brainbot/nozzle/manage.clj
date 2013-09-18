(ns brainbot.nozzle.manage
  (:require [brainbot.nozzle.routing-key :as rk]
            [brainbot.nozzle.sys :as sys]
            [brainbot.nozzle.inihelper :as inihelper]
            [brainbot.nozzle.dynaload :as dynaload]
            [brainbot.nozzle.worker :as worker]
            [brainbot.nozzle.mqhelper :as mqhelper]
            [brainbot.nozzle.vfs :as vfs])
  (:require [robert.bruce :refer [try-try-again]])
  (:require [clojure.tools.logging :as logging]
            [clj-logging-config.log4j :as log-config])
  (:require [clojure.data.json :as json])
  (:require [langohr.http :as rmqapi]
            [langohr.basic :as lb]
            [langohr.core :as rmq]
            [langohr.channel :as lch]))


(defn filter-by-vhost
  [vhost coll]
  (filter #(= vhost (:vhost %)) coll))

(defn filter-by-rkmap
  [rkmap coll]
  (filter (fn [{:keys [name]}]
            (= rkmap
               (select-keys
                (rk/map-from-routing-key-string name) (keys rkmap))))
          coll))

(defn num-messages-from-queue-state
  [queue-state vhost rkmap]
  (reduce + (map :messages (filter-by-rkmap rkmap (filter-by-vhost vhost queue-state)))))


(defn wait-for-zero-messages
  [get-num-messages]
  (loop  [zero-count 0]
    (if (= 0 (get-num-messages))
      (when (< zero-count 6)
        (Thread/sleep 500)
        (recur (inc zero-count)))
      (do
        (Thread/sleep 10000)
        (recur 0)))))


(defn start-synchronization
  [rmq-settings id filesystem]
  (let [conn (rmq/connect rmq-settings)
        ch (lch/open conn)]
    (mqhelper/initialize-rabbitmq-structures
     ch "listdir" id filesystem)
    (lb/publish ch id
                (rk/routing-key-string-from-map {:id id :filesystem filesystem :command "listdir"})
                (json/write-str {:path "/"}))
    (rmq/close ch)
    (rmq/close conn)))

(defn get-num-messages
  "return number of messages in the given rabbitmq vhost for the given
  filesystem and id/prefix"
  [vhost id filesystem]
  (num-messages-from-queue-state
   (rmqapi/list-queues)
   vhost
   {:id id
    :filesystem filesystem}))

(defn manage-filesystem*
  [rmq-settings id {:keys [fsid sleep-between-sync]}]
  (let [qname (rk/routing-key-string-from-map {:id id :filesystem fsid :command "*"})
        get-num-messages* #(get-num-messages (:vhost rmq-settings) id fsid)
        wait-idle #(wait-for-zero-messages get-num-messages*)]
    (logging/debug "waiting for" qname "to become idle")
    (wait-idle)
    (while true
      (logging/info "starting synchronization of" qname)
      (start-synchronization rmq-settings id fsid)
      (Thread/sleep 10000)
      (wait-idle)
      (logging/info "synchronization of" qname "finished. restarting in" sleep-between-sync "seconds")
      (Thread/sleep (* sleep-between-sync 1000)))))

(defn manage-filesystem
  [rmq-settings id fsextra]
  (try-try-again {:sleep (* 60 1000)
                  :tries :unlimited
                  :error-hook (fn [e] (logging/error "error in manage-filesystem" (:fsid fsextra) e))}
                 #(manage-filesystem* rmq-settings id fsextra)))

(defrecord ManageService [rmq-settings rmq-prefix filesystems]
  worker/Service
  (start [this]
    (doseq [fs filesystems]
      (future (manage-filesystem rmq-settings rmq-prefix fs)))))

(defn http-connect!
  [{:keys [api-endpoint username password]}]
  (logging/info "using rabbitmq api-endpoint" api-endpoint)
  (rmqapi/connect! api-endpoint username password))

(def runner
  (reify
    dynaload/Loadable
    inihelper/IniConstructor
    (make-object-from-section [this system section-name]
      (let [rmq-settings (-> system :config :rmq-settings)
            rmq-prefix (-> system :config :rmq-prefix)
            filesystem-names (sys/get-filesystems-for-section system section-name)
            filesystems (map #(vfs/make-additional-fs-map system %) filesystem-names)]
        (http-connect! rmq-settings)
        (->ManageService rmq-settings rmq-prefix filesystems)))))
