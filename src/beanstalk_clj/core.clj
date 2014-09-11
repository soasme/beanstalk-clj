(ns beanstalk-clj.core
  (:require [clojure.string :as string])
  (:use [slingshot.slingshot :only [throw+]]
        [clojure.java.io])
  (:import [java.net Socket]))

(def ^:const default-host "localhost")
(def ^:const default-port 11300)
(def ^:const default-priority 2147483648) ; 2^31
(def ^:const default-ttr 120)
(def ^:const crlf (str \return \newline))

(defn member? [list elt]
  "True if list contains at least one instance of elt"
  (cond
   (empty? list) false
   (= (first list) elt) true
   true (member? (rest list) elt)))

(defprotocol Interactive
  (close [this] "Close the connection")
  (read [this] "Read from connection")
  (write [this data] "Write data to connection")
  (interact [this command expected_ok expected_err])
  (interact-value [this command expected_ok])
  (interact-yaml [this command expected_ok])
  (interact-job [this command expected_ok])
  (interact-peek [this command]))


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
         resp (first data)]
     (cond
      (member? expected_ok resp)
      data

      (member? expected_err resp)
      (throw+ {:type :command-failure :message bin})

      true
      (throw+ {:type :unexpected-response :message bin}))))

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



;; (defn- with-beanstalkd*
;;   [f]
;;   (fn [beanstalkd & rest :as args]
;;     (if (and (thread-bound? *beanstalkd*)
;;              (not (identical? *beanstalkd* beanstalkd)))
;;       (f *beanstalkd* args)
;;       (f beanstalkd rest))))


(defmacro with-beanstalkd
  "Takes a url an config. That value is used to configure the subject
   of all of the operations within the dynamic scope of body of code."
  [config & body]
  `(binding [*beanstalkd* (beanstalkd-factory config)]
     ~@body))



(defn watch
  [beanstalkd tube]
  (interact beanstalkd
            (beanstalkd-cmd :watch tube)
            ["WATCHING"]
            []))
