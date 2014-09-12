# beanstalk-clj

`beanstalk-clj` is a simple beanstalkd client library for Clojure.
[beanstalkd] is a fast, distributed, in-memory workqueue service.

`beanstalk-clj` depends on:

* clj-yaml
* slingshot

## Usage

```clj
(def client (beanstalk-factory))
(def client (beanstalk-factory "localhost" 11300))
(def client (beanstalk-factory "localhost:11300"))
```

```clj
(put client "body")
(let [job (reserve client)]
  (:body job)
  (delete (:jid job)))
```

## License

Copyright © 2014 Lin Ju (soasme)

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
