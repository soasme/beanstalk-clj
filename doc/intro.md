# Introduction to beanstalk-clj

Getting started
---------------

Assume you have installed beanstalkd at the very begining.
To start our trip, simply start it by typing
`beanstalkd -VV` in shell. Let's see what it really happens!

```
~ % beanstalkd -VV
pid 6034
bind 4 0.0.0.0:11300
```

To use `beanstalk-clj` we have to use the library first and set up
a connection to an already running beanstalkd server.

```clj
(use 'beanstalk-clj)

; Use `#{host}:#{port}` format string to set up client:
(def client (beanstalkd-factory "localhost:11300"))

; Or 2 parameters: host, port
(def client (beanstalkd-factory "localhost" 11300))

; Or default host ("localhost"), default port (11300)
(def client (beanstalkd-factory))
```

Inspect beanstalkd verbose log:

```
accept 6
```

Basic Operation
---------------

Now that we have a connection set up, we can enqueue jobs:

```clj
=> (put client "message")
```

Inspect beanstalkd:

```
<6 command put
<6 job 1
>6 reply INSERTED 1
```

Or we can request jobs:

```clj
=> (let [job (reserve client)]
    (.body job))
```

Inspect beanstalkd:

```
<6 command reserve
>6 reply RESERVED 1 7
>6 job 1
```

Once we are done with processing a job, we have to mark it as
done, otherwise jobs are re-queued by beanstalkd after ttr
(time-to-run, 120 seconds default) is surpassed. A job is marked
as done by calling `delete`:

```clj
=> (delete job)
```

Inspect beanstalkd:

```
<6 command delete
>6 reply DELETED
```

If you use a timeout of 0, `reserve` will immediately return either
a job or `nil`

```clj
=> (reserve client :with-timeout 0)
nil
```

Inspect beanstalkd:

```
<6 command reserve-with-timeout
>6 reply TIMED_OUT
```

Note that beanstalk-clj requires a job bodies to be strings, otherwise
throwing an exception:

```clj
=> (put client 1234)

ExceptionInfo throw+: {:type :type-error, :message "Job body must be a String"}  beanstalk-clj.core/put (core.clj:192)
```

There is no restriction on what characters you can put in a job body,
so the can be used to hold artibrary binary data:

* If you want to send images, just `put` the image data as a string;
* If you want to send dictionary, just `put` the json or protobuf encoded string.

A more `clojure-idiom` interface would look something like following:

```clj
  => (with-beanstalkd (beanstalkd-factory)
#_=>  (use-tube "my-tube")
#_=>  (put "hello" :priority 0 :delay 0 :ttr 120))
nil

  => (with-beanstalkd (beanstalkd-factory)
#_=>  (watch-tube "my-tube")
#_=>  (let [job (reserve)]
#_=>   (println (.body job))
#_=>   (delete job)))
hello
nil
```
Inspect beanstalkd:

```
<7 command use
>7 reply USING my-tube

<7 command put
<7 job 4
>7 reply INSERTED 4
close 7

accept 7

<7 command watch
>7 reply WATCHING 2

<7 command reserve
>7 reply RESERVED 4 5
>7 job 4

<7 command delete
>7 reply DELETED

close 7
```

Now for saving some typing with a little macro:

```clj
  => (defmacro beanstalk [& body]
#_=>  `(with-beanstalkd (beanstalkd-factory)
#_=>   ~@body))
```

Tube Management
---------------

A single beanstalkd server can provide many different queues,
called `list-tubes` to see all available tubes:

```clj
=> (beanstalk (prn (list-tubes)))
("default")
nil
```

A beanstalkd client choose one tube into which its job are put.
This is the tube "used" by the client. To see what tube you
are currently using:

```clj
=> (beanstalk (prn (list-tube-used)))
"default"
nil
```

Unless told, otherwise, a client uses the `default` tube.
If you want to use a different tube:

```clj
  => (beanstalk
#_=>  (use-tube "foo")
#_=>  (prn (list-tube-used)))
"foo"
nil
```

If you decide to use a tube, that does not yet exist,
the tube is automatically created by beanstalk-clj:

```clj
  => (beanstalk
#_=>  (use-tube "foo")
#_=>  (prn (list-tubes)))
("default" "foo")
nil
```

Of course, you can always switch back to the default tube.
Tubes that don't have any client using or watching, vanish
automatically:

```clj
=> (beanstalk (use-tube "foo"))
nil

  => (beanstalk
#_=>  (prn (list-tubes))
#_=>  (prn (list-tube-used)))
("default")
"default"
nil
```

Further, a beanstalkd client can choose many tubes to reserve
jobs from. These tubes are `watched` by client. To see
what tubes you are currently watching:

```clj
=> (beanstalk (prn (watching)))
("default")
nil
```

To watch an additional tube:

```clj
  => (beanstalk
#_=>  (watch-tube "bar")
#_=>  (prn (watching)))
("default" "bar")
nil
```

As before, tubes that do not yet exist are created automatically
once you start watching them.

```clj
  => (beanstalk
#_=>  (watch-tube "bar")
#_=>  (prn (list-tubes)))
("default" "bar")
nil
```

To stop watching a tube:

```clj
  => (beanstalk
#_=>  (watch-tube "bar")
#_=>  (ignore "bar")
#_=>  (prn (watching)))
("default")
nil
```

You can't watch zero tubes. So if you try to ignore the last
tube you are watching, this is silently ignored.

To recap: each beanstalkd client manages two separate concerns: which tube
newly created jobs are put into, and which tube(s) jobs are reserved from.
Accordingly, there are two separate sets of functions for these concerns:

  - `use` and `using` affect where jobs are `put`;
  - `watch` and `watching` control where jobs are `reserve`d from.

Note that these concerns are fully orthogonal: for example, when you `use` a
tube, it is not automatically `watch`ed. Neither does `watch`ing a tube affect
the tube you are `using`.

Statistics
----------

Beanstalkd accumulated various statistics at the server/tube/job level.
Statistical details for a job can only be retrieved during the
job's lifecycle. If you try to access job stats after the job was delted,
you will get a command-failure exception:

```clj
  => (beanstalk
#_=>  (put "yo")
#_=>  (let [job (reserve)]
#_=>   (prn "stats: " (stats job))
#_=>   (delete job)
#_=>   (stats job)))
"stats: " {:ttr 120, :age 6829, :file 0, :kicks 0, :state "reserved", :releases 0, :id 2, :buries 0, :time-left 119, :timeouts 2, :delay 0, :tube "default", :reserves 3, :pri 2147483648}

ExceptionInfo throw+: {:type :command-failure, :status "NOT_FOUND", :results nil}  beanstalk-clj.core.Beanstalkd (core.clj:82)
```
Let's have a look at some numbers for the `default` tube:

```clj
=> (beanstalk (prn (stats-tube "default")))
{:current-jobs-ready 2, :current-jobs-buried 0, :current-jobs-urgent 0, :current-jobs-reserved 0, :name "default", :total-jobs 4, :cmd-pause-tube 0, :cmd-delete 2, :pause-time-left 0, :current-watching 3, :current-jobs-delayed 0, :pause 0, :current-waiting 0, :current-using 3}
nil
```

Finally, let's go into server-level statistics:

```clj
=> (beanstalk (prn (stats-beanstalkd)))
{:binlog-records-written 0, :job-timeouts 0, :cmd-reserve 9, :current-producers 0, :current-jobs-ready 0, :current-jobs-buried 0, :total-connections 35, :cmd-reserve-with-timeout 7, :max-job-size 65535, :cmd-peek-ready 1, :cmd-ignore 2, :current-jobs-urgent 0, :cmd-stats-tube 2, :current-jobs-reserved 0, :binlog-current-index 0, :total-jobs 13, :uptime 122, :cmd-release 2, :rusage-utime 0.002703, :cmd-bury 1, :binlog-records-migrated 0, :hostname "Soasme-Retina-MacBook-Pro.local", :cmd-stats-job 10, :cmd-list-tubes 4, :cmd-pause-tube 0, :current-tubes 1, :cmd-peek-buried 0, :cmd-peek-delayed 0, :cmd-put 13, :cmd-peek 2, :pid 24244, :cmd-list-tube-used 6, :cmd-kick 0, :cmd-list-tubes-watched 3, :cmd-delete 13, :id "6fbc537ca40afe4a", :binlog-oldest-index 0, :cmd-stats 3, :rusage-stime 0.006188, :cmd-use 1, :current-jobs-delayed 0, :binlog-max-size 10485760, :version 1.9, :current-waiting 0, :cmd-touch 0, :cmd-watch 3, :current-workers 0, :current-connections 1}
nil
```

Advanced Operation
------------------

In "Basic Operation" above, we discussed the typical lifecycle of a job:

     put            reserve               delete
    -----> [READY] ---------> [RESERVED] --------> *poof*


    (This picture was taken from beanstalkd's protocol documentation. It is
    originally contained in `protocol.txt`, part of the beanstalkd
    distribution.)

But besides `ready` and `reserved`, a job can also be `delayed` or `buried`.
Along with those states come a few transitions, so the full picture looks like
the following:

```
put with delay               release with delay
----------------> [DELAYED] <------------.
|                   |
| (time passes)     |
|                   |
put                  v     reserve       |       delete
-----------------> [READY] ---------> [RESERVED] --------> *poof*
^  ^                |  |
|   \  release      |  |
|    `-------------'   |
|                      |
| kick                 |
|                      |
|       bury           |
[BURIED] <---------------'
|
|  delete
`--------> *poof*


(This picture was taken from beanstalkd's protocol documentation. It is
originally contained in `protocol.txt`, part of the beanstalkd
distribution.)
```

Now let's have a practical look at those new possibilities. For a start, we can
create a job with a delay. Such a job will only be available for reservation
once this delay passes:

```clj
  => (beanstalk
#_=>  (put "yo" :delay 1)
#_=>   (prn (reserve :with-timeout 0))
#_=>   (let [job (reserve :with-timeout 1)]
#_=>    (prn (.body job))
#_=>    (delete job)))
nil
"yo"
nil
```

To release job will put it back into the tube it came from;
To bury job will put it aside and not available until execute `kick`;
To kick with a bound number will send many jobs to be alived again:
```clj
  => (beanstalk
#_=>  (let [jid (put "yo")  job (reserve)]
#_=>   (release job)
#_=>   (prn (:state (stats-job jid)))
#_=>   (reserve)
#_=>   (bury job)
#_=>   (prn (:state (stats-job jid)))
#_=>   (kick 1)
#_=>   (prn (:state (stats-job jid)))
#_=>   (reserve)
#_=>   (delete jid)))
"ready"
"buried"
"ready"
nil
```

Inspecting jobs
---------------

Peek command allow us to inpect jobs without reserving and modifying
their states, Note that this peek did not reserve the job:

```clj
=> (beanstak
    (let [jid (put "yo")]
     (prn (peek jid))
     (prn (:state (stats-job jid))))))
#<Job beanstalk_clj.core.Job@53273445>
"ready"
```

If you try to peek at a non-existing job, you'll get an exception:

```
=> (peek client 1234)

ExceptionInfo throw+: {:type :command-failure, :status "NOT_FOUND", :results nil}  beanstalk-clj.core.Beanstalkd (core.clj:82)
```

You can also use `peek-delayed` and `peek-buried` to inspect delayed jobs
and buried jobs.

Job Priorities
--------------

If need arises, you can override this behaviour by giving different jobs
different priorities. There are three hard facts to know about job priorities:

  1. Jobs with lower priority numbers are reserved before jobs with higher
  priority numbers.

  2. beanstalkd priorities are 32-bit unsigned integers (they range from 0 to
  2**32 - 1).

  3. beanstalkc uses 2**31 as default job priority
  (`beanstalkc.DEFAULT_PRIORITY`).

```clj
=> (beanstalk
                #_=>  (let [jid42 (put "42" :priority 42)
                #_=>        jid21 (put "21" :priority 21)
                #_=>        jid21x2 (put "21x2" :priority 21)]
                #_=>   (prn (. (reserve) body))
                 #_=>  (delete jid21)
                #_=>   (prn (. (reserve) body))
                #_=>   (delete jid21x2)
                #_=>   (prn (. (reserve) body))
                #_=>   (delete jid42)))
"21"
"21x2"
"42"
```

Fin!
----

```clj
=> (.close client)
```

That's it, for now. We've left a few capabilities untouched (touch and
time-to-run). But if you've really read through all of the above, send me a
message and tell me what you think of it. And then go get yourself a treat. You
certainly deserve it.

