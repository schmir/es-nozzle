(ns brainbot.nozzle.rmqstats
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
  (:require [clojure.core.async :refer [go thread chan <! <!! >! >!!] :as async])
  (:require [clojure.data.json :as json])
  (:require [overtone.at-at :as at-at])
  (:require [langohr.http :as rmqapi]
            [langohr.basic :as lb]
            [langohr.core :as rmq]
            [langohr.channel :as lch]))

(def my-pool (at-at/mk-pool))

(defn throw-management-api-error
  "throw a somewhat informative message about missing management rights"
  []
  (throw
   (ex-info
    "no response from RabbitMQ's management API. make sure you have added the management tag for the user in RabbitMQ"
    {:endpoint rmqapi/*endpoint*
     :username rmqapi/*username*})))

(defn list-queues
  "wrapper around langohr's http/list-queues. this one raises an error
  instead of returning nil when the user has no management rights for
  the RabbitMQ management plugin"
  [& args]
  (let [qs (apply rmqapi/list-queues args)]
    (when-not qs
      (throw-management-api-error))
    qs))



(defn doit
  [ch val]
  (go (async/>! ch val)))

(def ch (chan 5))

(defn call-and-put
  "call function fn with args and put result on channel ch if it's non-nil"
  [ch fn & args]
  (let [res (apply fn args)]
    (when-not (nil? res)
      (>!! ch res))))

(defn catch-errors
  "catch and log errors. at-at doesn't do it and what's worse, it
stops calling the scheduled functions"
  [f]
  (fn [& args]
    (try
      (apply f args)
      (catch Exception err
        (logging/error "error while calling" f ":" err)))))


(defn periodically-call-and-blocking-put
  [ms ch fn & args]
  (at-at/every ms
               (catch-errors #(apply call-and-put ch fn args))
               my-pool
               :fixed-delay true))

#_(periodically-call-and-blocking-put 1000 ch list-queues)

(defn pulse
  ([ms] (pulse (chan) ms))
  ([ch ms]
     (let [ctrl-ch (chan)]
       (async/go
        (loop [count 0]
          (>! ch {:count count})
          (async/alt!
           ctrl-ch ([v] (async/close! ch))
           (async/timeout ms) ([v] (recur (inc count))))))
       {:ch ch :pulse-ctrl-ch ctrl-ch})))

(defn stop-pulse
  [{pulse-ctrl-ch :pulse-ctrl-ch}]
  (async/close! pulse-ctrl-ch))


(defn pulse-fn
  "call function fn periodically, sleep ms milliseconds between two
calls, puts the result of the function calls on the channel ch"
  [ch ms f]
  (let [p (pulse ms)]
    (map< (fn [x] x) hidden-channel)))

(async/go )

;; #_(doL
;;   (async/go (doit ch 5))
;;   (async/<!! ch))
