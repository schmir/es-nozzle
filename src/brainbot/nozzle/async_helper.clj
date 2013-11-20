(ns brainbot.nozzle.async-helper
  "some small helpers for core.async"
  (:require [clojure.core.async :refer [go thread chan mult put! close! <! <!! >! >!!] :as async]))

(defn looping-go
  "call f in a loop from a go block. f must return a channel, from
which we read one message, put the result on dest-ch if given. sleep
ms milliseconds before calling f again"
  ([ms f]
     (looping-go ms f nil))
  ([ms f dest-ch]
     (assert (ifn? f) "f must be a function")
     (let [ctrl-ch (chan)]
       (go
        (try
          (loop []
            (let [r (<! (f))]
              (when-not (or (nil? r) (nil? dest-ch))
                (>! dest-ch r)))
            (async/alt!
             ctrl-ch ([v] nil)
             (async/timeout ms) ([v] (recur))))
          (finally
            (when-not (nil? dest-ch)
              (close! dest-ch)))))
       {::ctrl-ch ctrl-ch})))


(defn looping-thread
  "call f in a loop from a thread. sleep ms milliseconds between."
  [ms f]
  (assert (ifn? f) "f must be a function")
  (looping-go ms #(async/thread-call f)))


(defn looping-stop
  "stop function calling loop"
  [{ctrl-ch ::ctrl-ch}]
  (async/close! ctrl-ch))


(defn redirect-fn-to-channel
  "return a fn that - when called - calls f and additionally puts the
result on channel ch"
  [f ch]
  (fn []
    (let [res (f)]
      (when-not (nil? res)
        (>!! ch res))
      res)))


(defn when-loop-stopped
  "call fn f when loop is stopped"
  [{ctrl-ch ::ctrl-ch} f]
  (go
   (<! ctrl-ch)
   (f)))


(defn make-looping-thread-mult
  "call f in a loop from a thread. sleep ms milliseconds between.
put the result of calling f into a core.async mult. the mult is
returned inside the return value as :mult key
"
  [ms f]
  (assert (ifn? f) "f must be a function")
  (let [ch (chan)
        m (mult ch)
        wrap-f (redirect-fn-to-channel f ch)
        retval (assoc (looping-thread ms wrap-f)
                 :mult m)]
    ;; close the channel if the loop is stopped
    (when-loop-stopped retval #(close! ch))
    retval))

#_(defn mark
  []
  (go
   (let [c (System/currentTimeMillis)]
     (println "hello" c)
     c)))

;; (def m (make-looping-thread-mult 2000 #(let [c (System/currentTimeMillis)] (println "hello" c) c)))
;; (def ch (chan))




