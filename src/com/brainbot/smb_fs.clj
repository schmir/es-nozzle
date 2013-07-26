(ns com.brainbot.smb-fs
  (:require
   [clojure.string :as string])
  (:require [com.brainbot.vfs :as vfs])
  (:import [jcifs.smb SmbFile NtlmPasswordAuthentication SID ACE]))

(def ^:private group-like-types
  #{SID/SID_TYPE_ALIAS SID/SID_TYPE_DOM_GRP SID/SID_TYPE_WKN_GRP})


(defn- is-sid-group-like?
  [sid]
  (contains? group-like-types (.getType sid)))


(defn- ensure-endswith-slash
  [a-string]
  (if (.endsWith a-string "/")
    a-string
    (str a-string "/")))
(defn- smb-file-for-entry [fs entry]
  (SmbFile. (string/join [(:root fs) entry]) (:auth fs)))


(defn- convert-sid
  [sid]
  (let [name (.getAccountName sid)]
    (str (if (is-sid-group-like? sid)
           "GROUP"
           "USER")
         ":"
         name)))

(defn- convert-ace
  [ace]
  (if-not (zero? (bit-and (.getAccessMask ace) ACE/FILE_READ_DATA))
    (let [is-allow (.isAllow ace)
          sid (.getSID ace)]
      {:allow is-allow,
       :sid (convert-sid sid)})))


(defrecord SmbFilesystem [root auth]
  vfs/Filesystem
  (get-permissions [fs entry]
    (let [smb-file (smb-file-for-entry fs entry)
          acl (seq (.getSecurity smb-file true))]
      (remove nil? (map convert-ace acl))))

  (stat [fs entry]
    (let [smb-file (smb-file-for-entry fs entry)
          is-directory (.isDirectory smb-file)
          is-file (.isFile smb-file)
          common-result {:mtime (quot (.lastModified smb-file) 1000)
                         :type (cond
                                 is-directory
                                   :directory
                                 is-file
                                   :file
                                 :else
                                   :other)}]
      (if is-file
        (assoc common-result
          :size (.length smb-file))
        common-result)))

  (join [fs parts]
    (string/join "/" parts))

  (listdir [fs dir]
    (seq (.list (smb-file-for-entry fs (ensure-endswith-slash dir))))))


(defn make-smb-filesystem-from-map
  [section]
  (let [smb-auth (NtlmPasswordAuthentication. (:domain section) (:username section) (:password section))
        path (ensure-endswith-slash (:path section))]
    (->SmbFilesystem path smb-auth)))