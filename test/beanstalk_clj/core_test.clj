(ns beanstalk-clj.core-test
  (:require [clojure.test :refer :all]
            [beanstalk-clj.core :refer :all]))

(deftest watch-tube
  (testing "Watch tube"
    (let [bt (beanstalkd-factory)]
      (is (= (watch bt "default") ["WATCHING" "1"])))))
