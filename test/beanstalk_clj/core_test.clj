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

(deftest enqueue-jobs
  (let [bt (beanstalkd-factory)]
    (testing "Put body (with default options)"
      (is (= (number? (put bt "body")))))
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

(deftest kick-bound
  (let [producer (beanstalkd-factory)
        consumer (beanstalkd-factory)]
    (watch producer "default")
    (use consumer "default")
    (testing "Kick at most bound jobs into the ready queue")))

(deftest test-tubes
  (let [bt (beanstalkd-factory)]
    (testing "List default tubes"
      (is (= ["default"] (list-tubes bt))))

    (testing "List default tube used"
      (is (= "default" (list-tube-used bt))))

    (testing "Get using"
      (is (= "default" (using bt))))


    (let [_ (use bt "test-tube")]
      (testing "List available tubes"
        (is (= ["default" "test-tube"] (list-tubes bt))))

      (testing "List tube used"
        (is (= "test-tube" (list-tube-used bt))))

      (testing "Get using"
        (is (= "test-tube" (using bt)))))

    (let [_ (watch bt "test-tube")]
      (testing "List watching tubes"
        (is (= ["default" "test-tube"] (watching bt))))

      (testing "List watching tubes"
        (is (= ["default" "test-tube"] (list-tubes-watched bt)))))))

