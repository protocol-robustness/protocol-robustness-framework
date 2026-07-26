(ns resolver-sim.monitoring
  "JMX monitoring for Protocol Robustness Framework.

   Registers a DynamicMBean exposing run counters and status on the platform
   MBeanServer.  Remote JMX must be configured via JVM system properties:
     -Dcom.sun.management.jmxremote.port=50051
     -Dcom.sun.management.jmxremote.authenticate=false
     -Dcom.sun.management.jmxremote.ssl=false

   Connect via JConsole, VisualVM, or any JMX client."
  (:import [javax.management
            DynamicMBean MBeanInfo MBeanAttributeInfo MBeanOperationInfo
            MBeanConstructorInfo MBeanParameterInfo
            ObjectName MBeanNotificationInfo]
           [java.lang.management ManagementFactory])
  (:gen-class))

(def ^:private mbean-name (ObjectName. "resolver-sim:type=Monitoring"))

(def ^:private counters
  (atom {:scenarios-run 0
         :scenarios-passed 0
         :scenarios-failed 0
         :evidence-captured 0
         :start-ms (System/currentTimeMillis)
         :running false}))

(defn- attr [name type desc]
  (MBeanAttributeInfo. name type desc true false false))

(defn- build-mbean-info []
  (MBeanInfo.
   "resolver-sim.monitoring"
   "Resolver Simulation Monitoring"
   (into-array MBeanAttributeInfo
     [(attr "ScenariosRun" "long" "Total scenarios run")
      (attr "ScenariosPassed" "long" "Scenarios passed")
      (attr "ScenariosFailed" "long" "Scenarios failed")
      (attr "EvidenceCaptured" "long" "Evidence records captured")
      (attr "UptimeSeconds" "long" "Uptime in seconds")
      (attr "Running" "boolean" "Whether monitoring is active")])
   (make-array MBeanConstructorInfo 0)
   (into-array MBeanOperationInfo
     [(MBeanOperationInfo. "resetCounters" "Reset all counters"
                           (make-array MBeanParameterInfo 0)
                           "void" MBeanOperationInfo/ACTION)])
   (make-array MBeanNotificationInfo 0)))

(defn- build-dynamic-mbean []
  (let [mbean-info (atom nil)]
    (reify DynamicMBean
      (getAttribute [this name]
        (case name
          "ScenariosRun" (long (:scenarios-run @counters))
          "ScenariosPassed" (long (:scenarios-passed @counters))
          "ScenariosFailed" (long (:scenarios-failed @counters))
          "EvidenceCaptured" (long (:evidence-captured @counters))
          "UptimeSeconds" (long (/ (- (System/currentTimeMillis) (:start-ms @counters)) 1000))
          "Running" (:running @counters)
          (throw (javax.management.AttributeNotFoundException. name))))
      (setAttribute [this attribute]
        (throw (javax.management.AttributeNotFoundException. (.getName attribute))))
      (getAttributes [this names]
        (javax.management.AttributeList.
         (mapv (fn [n] (javax.management.Attribute. n (.getAttribute this n))) names)))
      (setAttributes [this attributes] attributes)
      (invoke [this action-name params sig]
        (when (= "resetCounters" action-name)
          (swap! counters assoc :scenarios-run 0 :scenarios-passed 0 :scenarios-failed 0 :evidence-captured 0))
        nil)
      (getMBeanInfo [this] (or @mbean-info (reset! mbean-info (build-mbean-info)))))))

(defn start!
  "Register the monitoring MBean on the platform MBeanServer.
   Port is accepted for compatibility but ignored — JMX port is configured
   via JVM system properties."
  [& [port]]
  (let [server (ManagementFactory/getPlatformMBeanServer)]
    (when-not (.isRegistered server mbean-name)
      (.registerMBean server (build-dynamic-mbean) mbean-name)
      (swap! counters assoc :running true :start-ms (System/currentTimeMillis)))
    nil))

(defn stop!
  "Unregister the monitoring MBean."
  []
  (try
    (let [server (ManagementFactory/getPlatformMBeanServer)]
      (.unregisterMBean server mbean-name))
    (catch Exception _))
  (swap! counters assoc :running false)
  nil)

(defn -main
  "Start JMX monitoring system and block waiting for shutdown signal."
  [& args]
  (println "resolver-sim JMX monitoring starting...")
  (start!)
  (println "Monitoring MBean registered: resolver-sim:type=Monitoring")
  (println "Enable remote JMX via JVM properties:")
  (println "  -Dcom.sun.management.jmxremote.port=<port>")
  (println "  -Dcom.sun.management.jmxremote.authenticate=false")
  (println "  -Dcom.sun.management.jmxremote.ssl=false")
  (println "Press Ctrl+C to stop.")
  @(promise))

(defn increment-scenarios-run
  "Increment the scenarios-run counter."
  []
  (swap! counters update :scenarios-run inc)
  nil)

(defn increment-scenarios-passed
  "Increment the scenarios-passed counter."
  []
  (swap! counters update :scenarios-passed inc)
  nil)

(defn increment-scenarios-failed
  "Increment the scenarios-failed counter."
  []
  (swap! counters update :scenarios-failed inc)
  nil)

(defn increment-evidence-captured
  "Increment the evidence-captured counter."
  []
  (swap! counters update :evidence-captured inc)
  nil)

(defn counters-snapshot
  "Return a snapshot of the current counter values."
  []
  @counters)
