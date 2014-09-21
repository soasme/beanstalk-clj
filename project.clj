(defproject beanstalk-clj "0.1.3"
  :description "A simple beanstalkd client library for Clojure."
  :url "http://github.com/soasme/beanstalk-clj"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [clj-yaml "0.4.0"]
                 [slingshot "0.10.3"]]
  :repl-options {:init-ns beanstalk-clj.core}
 )
