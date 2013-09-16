(ns brainbot.nozzle.vfs
  (:require [clojure.tools.logging :as logging])
  (:require [brainbot.nozzle.inihelper :as inihelper]
            [brainbot.nozzle.fsfilter :as fsfilter]
            [brainbot.nozzle.misc :as misc]))


(defprotocol Filesystem
  "filesystem"
  (access-denied-exception? [fs exc] "access denied?")
  (extract-content [fs entry] "extract content from file")
  (get-permissions [fs entry] "get permissions")
  (stat [fs path] "stat entry")
  (join [fs parts] "join parts")
  (listdir [fs dir] "list directory"))


(def ^{:doc "load filesystem from ini section"
       :private true}
  dynaload-filesystem
  (comp
   (partial inihelper/ensure-protocol Filesystem)
   inihelper/dynaload-section))

(defn make-filesystem
  [system section-name]
  (let [fs (dynaload-filesystem system section-name)
        remove (misc/trimmed-lines-from-string
                (get-in system [:iniconfig section-name "remove"]))]
    (assoc fs
      :remove-filters (->> remove
                           (map (partial fsfilter/make-filter system))
                           (map fsfilter/make-match-entry?))
      :fsid section-name)))


(defn- listdir-catch-access-denied
  "call listdir, catch access denied errors, log an error for them and
  pretend the directory is empty"
  [fs path]
  (try
    (listdir fs path)
    (catch Exception err
      (if (access-denied-exception? fs err)
        (do
          (logging/info "access denied in listdir" {:fsid (:fsid fs), :path path})
          [])
        (throw err)))))


(defn- make-safe-stat-entry
  [fs path]
  (fn [entry]
    (try
      {:relpath entry
       :stat (stat fs (join fs [path entry]))}
      (catch Exception err
        {:relpath entry
         :error (str err)}))))

(defn- listdir-and-stat*
  [fs path]
  (map (make-safe-stat-entry fs path)
       (listdir-catch-access-denied fs path)))

(defn- apply-filters
  "apply filesystem filters to list of entries"
  [{remove-filters :remove-filters :as fs} path entries]
  (reduce
   (fn [entries filter-fun]
     (remove filter-fun entries))
   entries
   remove-filters))

(defn listdir-and-stat
  [fs path]
  (apply-filters fs path (listdir-and-stat* fs path)))
