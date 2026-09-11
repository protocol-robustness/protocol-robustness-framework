(ns resolver-sim.logging
  "Lightweight, zero-config logging facade.

   Design goals:
   - never throw
   - safe defaults (stdout EDN)
   - dynamic bindings for logger + context
   - simple structured events for future adapters")

(def ^:private levels #{:trace :debug :info :warn :error})

(def ^:dynamic *log-context* {})

(defn- now-ts []
  (try
    (.toString (java.time.Instant/now))
    (catch Throwable e
      (str "unknown (" (.getMessage e) ")"))))

(defn- default-logger [event]
  (try
    (prn event)
    (catch Throwable e
      (.println *err* (str "LOGGER-FAILURE in default-logger: " (.getMessage e))))))

(def ^:dynamic *logger* default-logger)

(defn- normalize-level [level]
  (if (contains? levels level) level :info))

(defn- safe-context [ctx]
  (if (map? ctx) ctx {}))

(defn- emit! [event]
  (try
    (let [logger *logger*]
      (if (fn? logger)
        (try
          (logger event)
          (catch Throwable e
            (.println *err* (str "LOGGER-FAILURE in logger fn: " (.getMessage e)))
            (default-logger event)))
        (default-logger event)))
    (catch Throwable e
      (.println *err* (str "LOGGER-FAILURE in emit!: " (.getMessage e)))))
  nil)

(defn log!
  "Emit structured log event.
   (log! level message)
   (log! level message context-map)"
  ([level message]
   (log! level message nil))
  ([level message context]
   (try
     (let [ctx (merge (safe-context *log-context*) (safe-context context))
           event (cond-> {:ts (now-ts)
                          :level (normalize-level level)
                          :message (str (or message ""))}
                   (seq ctx) (assoc :context ctx))]
       (emit! event))
     (catch Throwable e
       (.println *err* (str "LOGGER-FAILURE in log!: " (.getMessage e)))))))

(defn trace!
  ([message] (log! :trace message))
  ([message context] (log! :trace message context)))

(defn debug!
  ([message] (log! :debug message))
  ([message context] (log! :debug message context)))

(defn info!
  ([message] (log! :info message))
  ([message context] (log! :info message context)))

(defn warn!
  ([message] (log! :warn message))
  ([message context] (log! :warn message context)))

(defn error!
  ([message] (log! :error message))
  ([message context] (log! :error message context)))

(defmacro with-log-context
  "Merge run-scoped context into emitted events."
  [context & body]
  `(binding [*log-context* (merge (if (map? *log-context*) *log-context* {})
                                  (if (map? ~context) ~context {}))]
     ~@body))

(defmacro with-timing
  "Times an expression and logs duration in ms."
  [label & body]
  `(let [start# (System/nanoTime)]
     (try
       (let [result# (do ~@body)
             elapsed-ms# (/ (double (- (System/nanoTime) start#)) 1000000.0)]
         (info! (str "timing: " ~label)
                {:label ~label :duration-ms elapsed-ms#})
         result#)
       (catch Throwable t#
         (let [elapsed-ms# (/ (double (- (System/nanoTime) start#)) 1000000.0)]
           (error! (str "timing-failed: " ~label)
                   {:label ~label :duration-ms elapsed-ms# :error (.getMessage t#)}))
         (throw t#)))))