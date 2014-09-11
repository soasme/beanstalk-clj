(ns beanstalk-clj.core
  (:use [slingshot.slingshot :only [throw+]]))

(def ^:const default-host "localhost")
(def ^:const default-port 11300)
(def ^:const default-priority 2147483648) ; 2^31
(def ^:const default-ttr 120)
(def ^:const crlf (str \return \newline))


(def beanstalkd-factory
  [config])

(defn- with-beanstalkd*
  [f]
  (fn [beanstalkd & rest :as args]
    (if (and (thread-bound? *beanstalkd*)
             (not (identical? *beanstalkd* beanstalkd)))
      (f *beanstalkd* args)
      (f beanstalkd rest)))

(defmacro with-beanstalkd
  "Takes a url an config. That value is used to configure the subject
   of all of the operations within the dynamic scope of body of code."
  [config & body]
  `(binding [*beanstalkd* (beanstalkd-factory config)]
     ~@body))
