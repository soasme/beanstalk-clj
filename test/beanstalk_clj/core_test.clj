(ns beanstalk-clj.core-test
  (:require [clojure.test :refer :all]
            [slingshot.test :refer :all]
            [clojure.string :as string]
            [beanstalk-clj.core :refer :all]))

(defn slingshot-exception-class
  "Return the best guess at a slingshot exception class."
  []
  (try
    (Class/forName "slingshot.Stone")
    (catch Exception _
      (let [ei (Class/forName "slingshot.ExceptionInfo")]
        (if (and (resolve 'clojure.core/ex-info)
                 (resolve 'clojure.core/ex-data))
          (Class/forName "clojure.lang.ExceptionInfo")
          ei)))))

(defmacro is-thrown+?
  "clojure.test clause for testing that a slingshot exception is thrown."
  [& body]
  `(is (~'thrown? ~(slingshot-exception-class) ~@body)))

(defmacro is-thrown-with-msg+?
  "clojure.test clause for testing that a slingshot exception is thrown."
  [& body]
  `(is (~'thrown-with-msg? ~(slingshot-exception-class) ~@body)))

(deftest watch-tube
  (testing "Watch tube"
    (let [bt (beanstalkd-factory)]
      (is (= (number? (first (watch bt "default"))))))))

(deftest put-body
  (let [bt (beanstalkd-factory)]
    (watch bt "default")
    (testing "Put body (with default options)"
      (is (= (number? (first (put bt "body"))))))
    (testing "Put too big body")
    (testing "Put non-str body"
      (is-thrown+? {:type :type-error :message "Job body must be a String"} (put bt 1234)))))

(deftest reserve-body
  (let [producer (beanstalkd-factory)
        consumer (beanstalkd-factory)]
    (watch producer "default")
    (use consumer "default")
    (let [handler (fn [timeout]
                    (let [_ (put producer "body")
                          job (reserve consumer :with-timeout timeout)]
                      (is (:reserved job))
                      (is (number? (:jid job)))
                      (is (= (:size job) (.length "body")))
                      (is (= (:body job) "body"))))]
      ; FIXME
      ;(testing "Reserve with timeout"
      ;  (handler 1))
      (testing "Reserve without timeout"
        (handler nil)))))
