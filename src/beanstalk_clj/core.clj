(ns beanstalk-clj.core
  (:require [clojure.string :as string]
            [clj-yaml.core :as yaml])
  (:use [slingshot.slingshot :only [try+ throw+]]
        [clojure.java.io])
  (:refer-clojure :exclude [read peek])
  (:import [java.net Socket]))

(def ^:const default-host "localhost")
(def ^:const default-port 11300)
(def ^:const default-priority 2147483648) ; 2^31
(def ^:const default-ttr 120)
(def ^:const crlf (str \return \newline))
(def ^:dynamic *beanstalkd* nil)

(defn member? [list elt]
  "True if list contains at least one instance of elt"
  (cond
   (empty? list) false
   (= (first list) elt) true
   true (recur (rest list) elt)))

(defprotocol Interactive
  (close [this] "Close the connection")
  (read [this] "Read from connection")
  (write [this data] "Write data to connection")
  (interact [this command expected_ok expected_err])
  (interact-value
   [this command expected_ok expected_err])
  (interact-job
   [this command expected_ok expected_err]
   [this command expected_ok expected_err reserved?])
  (interact-yaml [this command expected_ok expected_err])
  (interact-peek [this command]))

(defprotocol JobOperatation
  (del [this] "Delete job")
  (stat [this] "Statistics"))

(deftype Job [consumer jid length body reserved?])

(deftype Beanstalkd [socket reader writer]
  Interactive
  (close
   [this]
   (.close socket))

  (read
   [this]
   (let [builder (StringBuilder.)]
     (loop [frag (.read reader)]
       (cond
        (neg? frag)
        (str builder)

        (and (= \newline (char frag))
             (> (.length builder) 1)
             (= (char (.charAt builder (- (.length builder) 1))) \return))
        (str (.substring builder 0 (- (.length builder) 1)))

        true
        (do (.append builder (char frag))
          (recur (.read reader)))))))

  (write
   [this data]
   (doto writer
     (.write data)
     (.write crlf)
     (.flush)))

  (interact
   [this command expected_ok expected_err]
   (write this command)
   (let [bin (read this)
         data (string/split bin #" ")
         [status & results] data]
     (cond
      (member? expected_ok status)
      results

      (member? expected_err status)
      (throw+ {:type :command-failure :status status :results results})

      true
      (throw+ {:type :unexpected-response :status status :results results}))))

  (interact-value
   [this command expected_ok expected_err]
   (first (interact this command expected_ok expected_err)))

  (interact-job
   [this command expected_ok expected_err reserved?]
   (let [[jid size] (interact this command expected_ok expected_err)
         bin (read this)]
     (Job. this (bigint jid) (Integer/parseInt size) bin reserved?)))


  (interact-peek
   [this command]
   (try+
    (interact-job this command ["FOUND"] ["NOT_FOUND"] false)))

  (interact-yaml
   [this command expected_ok expected_err]
   (let [[_ size] (interact this command expected_ok expected_err)
         bin (read this)]
     (yaml/parse-string bin)))
  )


(defn beanstalkd-factory
  ([]
   (beanstalkd-factory default-host default-port))
  ([config]
  (let [[host port-str] (string/split config #":")
        port (Integer/parseInt port-str)]
    (beanstalkd-factory host port)))
  ([host port]
   (let [socket (java.net.Socket. host port)]
     (Beanstalkd. socket (reader socket) (writer socket)))))


(defn beanstalkd-cmd
  [cmd & args]
  (if (nil? args)
    (name cmd)
    (str (name cmd) " " (str (reduce #(str % " " %2) args)))))


(defn beanstalkd-data
  [data]
  (str data))


(defn add-method
  [^clojure.lang.MultiFn multifn dispatch-val method]
  (. multifn addMethod dispatch-val method))



(defn- with-beanstalkd*
  [f]
   (fn [& [first & rest :as args]]
     (if (instance? Job first)
       (apply f args)
       (if (and (thread-bound? #'*beanstalkd*)
                (not (identical? *beanstalkd* first)))
         (apply f *beanstalkd* args)
         (apply f first rest)))))


(defmacro with-beanstalkd
  "Takes a url an config. That value is used to configure the subject
   of all of the operations within the dynamic scope of body of code."
  [beanstalkd & body]
  `(binding [*beanstalkd* ~beanstalkd]
     (let [rs# (do ~@body)]
       (.close *beanstalkd*)
       rs#)))


(defn inject-beanstalkd
  [multifn]
  (let [f ((methods multifn) Beanstalkd)]
    (add-method multifn Beanstalkd (with-beanstalkd* f))))

(defmacro ^:private defmethod-beanstalkd
  [name & body]
  `(do
     (defmethod ~name Beanstalkd ~@body)
     (inject-beanstalkd ~name)))

(defmacro ^:private defop
  [name & body]
  `(do
     (defn ~name ~@body)
     (alter-var-root (var ~name) with-beanstalkd*)))

(defop put
  ([beanstalkd body & {:keys [priority delay ttr]
                       :or {priority default-priority
                            delay 0
                            ttr default-ttr}}]
   (if (instance? String body)
     (let [cmd (str (beanstalkd-cmd :put
                               priority
                               delay
                               ttr
                               (.length body))
                    crlf
                    body)]
       (bigint (interact-value beanstalkd
                 cmd
                 ["INSERTED"]
                 ["JOB_TOO_BIG" "BURIED" "DRAINING"])))
     (throw+ {:type :type-error :message "Job body must be a String"}))))

(defop reserve
  ([beanstalkd & {:keys [with-timeout]
                  :or {with-timeout nil}}]
  (try+
   (let [cmd (if (nil? with-timeout)
               (beanstalkd-cmd :reserve)
               (beanstalkd-cmd :reserve-with-timeout with-timeout))]
     (interact-job beanstalkd
                   cmd
                   ["RESERVED"]
                   ["DEADLINE_SOON" "TIMED_OUT"]
                   true))
   (catch [:type :command-failure] {:keys [status results]}
     (cond (= status "TIMED_OUT")
           nil
           true
           (throw+))))))



(defop peek
  [beanstalkd jid]
  (interact-peek beanstalkd
                 (beanstalkd-cmd :peek jid)))

(defop peek-ready
  [beanstalkd]
  (interact-peek beanstalkd
                 (beanstalkd-cmd :peek-ready)))

(defop peek-delayed
  [beanstalkd]
  (interact-peek beanstalkd
                 (beanstalkd-cmd :peek-delayed)))

(defop peek-buried
  [beanstalkd]
  (interact-peek beanstalkd
                 (beanstalkd-cmd :peek-buried)))


(defop list-tubes
  [beanstalkd]
  (interact-yaml beanstalkd
                 (beanstalkd-cmd :list-tubes)
                 ["OK"]
                 []))

(defop list-tube-used
  [beanstalkd]
  (interact-value beanstalkd
                  (beanstalkd-cmd :list-tube-used)
                  ["USING"]
                  []))

(defop using
  [beanstalkd]
  (list-tube-used beanstalkd))


(defop use-tube
  [beanstalkd tube]
  (interact-value beanstalkd
            (beanstalkd-cmd :use tube)
            ["USING"]
            []))

(defop list-tubes-watched
  [beanstalkd]
  (interact-yaml beanstalkd
                 (beanstalkd-cmd :list-tubes-watched)
                 ["OK"]
                 []))

(defop watching
  [beanstalkd]
  (list-tubes-watched beanstalkd))


(defop watch-tube
  [beanstalkd tube]
  (interact beanstalkd
            (beanstalkd-cmd :watch tube)
            ["WATCHING"]
            []))

(defop ignore
  [beanstalkd tube]
  (interact-value beanstalkd
                       (beanstalkd-cmd :ignore tube)
                       ["WATCHING"]
                       ["NOT_IGNORED"]))

(defop stats-tube
  [beanstalkd tube]
  (interact-yaml beanstalkd
                 (beanstalkd-cmd :stats-tube tube)
                 ["OK"]
                 ["NOT_FOUND"]))

(defop pause-tube
  [beanstalkd tube delay]
  (interact beanstalkd
            (beanstalkd-cmd :pause-tube tube delay)
            ["PAUSED"]
            ["NOT_FOUND"]))

(defn ^:private job-dispatcher
  ([] Beanstalkd)
  ([& [instance & rest :as args]]
    (println args)
    (if (instance? Job instance)
        Job
        Beanstalkd)))

(defop stats-job
  ([beanstalkd jid]
  (interact-yaml beanstalkd
                 (beanstalkd-cmd :stats-job jid)
                 ["OK"]
                 ["NOT_FOUND"]))
  ([job]
   (stats-job (.consumer job) (.jid job))))


(defop stats-beanstalkd
  [beanstalkd]
  (interact-yaml beanstalkd
                  (beanstalkd-cmd :stats)
                  ["OK"]
                  []))

(defop kick-job
  [beanstalkd jid]
  (interact beanstalkd
            (beanstalkd-cmd :kick-job jid)
            ["KICKED"]
            ["NOT_FOUND"]))

(defn ^:private job-dispatcher
  [instance & rest]
  (if (instance? Job instance)
      Job
      Beanstalkd))

(defmulti kick job-dispatcher)


(defmethod-beanstalkd kick
  ([beanstalkd bound]
   (interact-value beanstalkd
                   (beanstalkd-cmd :kick bound)
                   ["KICKED"]
                   [])))

(defmethod kick Job
  [job]
  (kick-job (.consumer job) (.jid job)))

(defn- job-priority
  [job]
  (let [stats (stats-job job)]
    (if (nil? stats)
      default-priority
      (:pri stats))))

(defmulti bury job-dispatcher)

(defmethod-beanstalkd bury
  ([beanstalkd jid priority]
    (interact beanstalkd
              (beanstalkd-cmd :bury jid priority)
              ["BURIED"]
              ["NOT_FOUND"]))
  ([beanstalkd jid]
   (bury beanstalkd jid default-priority)))

(defmethod bury Job
  ([job priority]
   (bury (.consumer job) (.jid job) priority))
  ([job]
   (bury (.consumer job) (.jid job) (job-priority job))))



(defmulti release job-dispatcher)

(defmethod-beanstalkd release
  [beanstalkd jid & {:keys [priority delay]
                     :or {priority default-priority
                          delay 0}}]
  (interact beanstalkd
             (beanstalkd-cmd :release jid priority delay)
             ["RELEASED" "BURIED"]
             ["NOT_FOUND"]))

(defmethod release Job
  [job & {:keys [priority delay]
          :or {priority default-priority ; FIXME: or stats
               delay 0}}]
  (release (.consumer job)
           (.jid job)
           :priority priority
           :delay delay))

(defmulti touch job-dispatcher)

(defmethod-beanstalkd touch
  [beanstalkd jid]
  (interact beanstalkd
            (beanstalkd-cmd :touch (first rest))
            ["TOUCHED"]
            ["NOT_FOUND"]))

(defmethod touch Job
  [job]
  (touch (.consumer job) (.jid job)))

(defmulti delete job-dispatcher)

(defmethod-beanstalkd delete
  [beanstalkd jid]
  (interact beanstalkd
            (beanstalkd-cmd :delete jid)
            ["DELETED"]
            ["NOT_FOUND"]))

(defmethod delete Job
  [job]
  (delete (.consumer job) (.jid job)))

