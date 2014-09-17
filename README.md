# beanstalk-clj

![Build Status](https://travis-ci.org/soasme/beanstalk-clj.svg?branch=master)

`beanstalk-clj` is a [Clojure](http://clojure.org/) library for
[Beanstalkd](http://kr.github.io/beanstalkd/).

## Installation

To include beanstalk-clj in your project, simply add the
following to your `project.clj` dependencies:

```clojure
[beanstalk-clj "0.1.1"]
```
## State

Although it's in an early state of development(beanstalk-clj API
subject to change), beanstalk-clj has fully implemented beanstalkd
protocol.

At the moment, you will have to look at the source once you've loaded
beanstalk-clj up to get around the API. Documentation is coming soon.

## Usage

 basic REPL client declaration:

```clj
(def client (beanstalk-factory))

(def client (beanstalk-factory "localhost" 11300))

(def client (beanstalk-factory "localhost:11300"))
```

All queue operation accept a first argument indicating the client
for that operation.

```clj
=> (put client "body")
1N
=> (reserve client)
#<Job beanstalk_clj.core.Job@3695149e>
```

You can optionally provide configuration using dynamic scope via `with-beanstalkd`:

```clj
=> (with-beanstalkd (beanstalkd-factory)
    (println (put "body")))
1N
=> (with-beanstalkd (beanstalkd-factory)
    (println (reserve)))
#<Job beanstalk_clj.core.Job@52dec7eb>
```

After reserved, delete job:

```clj
=> (with-beanstalkd (beanstalkd-factory)
    (let [job (reserve)]
     (delete job))
```

## License

Copyright © 2014 Lin Ju (soasme)

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
