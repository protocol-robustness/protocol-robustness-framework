(ns resolver-sim.db.metabase-provision
  "Idempotent Metabase provisioning for the PRF / XTDB Explorer.

   Brings a local Metabase (compose `analytics` profile, image
   metabase/metabase:v0.58.31.x) to a defined, reproducible state against the
   already-frozen XTDB Temporal Explorer data model:

     ensure admin setup → ensure read-only XTDB datasource → ensure collection
     → ensure native-SQL questions → ensure 4-tab dashboard → print URL

   This is a disposable LOCAL demo bootstrap. The definitions below ARE the
   source of truth (not checked-in Metabase application state), and the bootstrap
   is idempotent: re-runs create no duplicate datasource / collection / question
   / dashboard / dashboard-card.

   v0.58 API notes:
     - Setup token is at GET /api/session/properties under kebab-case
       `setup-token`.
     - Dashboards are updated whole via PUT /api/dashboard/:id (there is no
       `/cards` / `/tabs` sub-endpoint); dashcard fields are snake_case
       (`size_x`, `size_y`).

   Run: bb explorer:metabase"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [resolver-sim.db.xtdb :as xtdb]
            [next.jdbc :as jdbc])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse]
           [java.net.http HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def base-url "http://localhost:3000")

(def admin-email "explorer@example.com")
(def admin-password "xtdb-demo-pass-1")
(def site-name "PRF / XTDB")

(def collection-name "PRF / XTDB Explorer")
(def dashboard-name "PRF / XTDB Explorer")

(def xtdb-db-name "XTDB")
(def xtdb-db-details
  {:host "xtdb" :port "5432" :dbname "xtdb" :user "xtdb" :password "xtdb" :ssl false})

(def disclaimer-text
  "Derived evidence index\n\nThese rows are projections over completed/rooted PRF artifacts.\nXTDB does not confer authority or replace artifact verification.\n\nOn the Root Convergence tab: comparison scope is explicit and semantic equivalence is never inferred from database fields.")

(defonce ^HttpClient client (HttpClient/newHttpClient))

;; ---------------------------------------------------------------------------
;; HTTP
;; ---------------------------------------------------------------------------

(defn- call!
  "Metabase API request; returns parsed body with :http/status."
  [method path & [{:keys [session body]}]]
  (let [builder (doto (HttpRequest/newBuilder (URI/create (str base-url path)))
                  (.header "Content-Type" "application/json")
                  (.header "Accept" "application/json"))
        _ (when session (.header builder "X-Metabase-Session" session))
        body-pub (if body
                   (HttpRequest$BodyPublishers/ofString (json/write-str body))
                   (HttpRequest$BodyPublishers/noBody))
        _ (.method builder (name method) body-pub)
        resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))
        raw (.body resp)
        parsed (try (json/read-str raw :key-fn keyword)
                    (catch Exception _ raw))]
    (if (map? parsed)
      (assoc parsed :http/status (.statusCode resp))
      {:http/status (.statusCode resp) :data parsed})))

(defn- log [& xs] (println (str/join " " (map str xs))))

;; ---------------------------------------------------------------------------
;; Setup / session
;; ---------------------------------------------------------------------------

(defn- ensure-admin!
  "Perform initial setup if needed; return session id."
  []
  (let [props (call! :get "/api/session/properties" {})
        has-user (:has-user-setup props)
        token  (:setup-token props)]
    (if has-user
      (do (log "Metabase already set up; logging in.")
          (:id (call! :post "/api/session"
                      {:body {:username admin-email :password admin-password}})))
      (do (log "Performing initial Metabase setup.")
          (let [resp (call! :post "/api/setup"
                            {:body {:token token
                                    :user {:first_name "Explorer" :last_name "Demo"
                                           :email admin-email :password admin-password
                                           :site_name site-name}
                                    :prefs {:site_name site-name}
                                    :database {:engine "postgres" :name xtdb-db-name
                                               :details xtdb-db-details}}})]
            (if (:id resp)
              (:id resp)
              (throw (ex-info "Metabase setup failed" {:response resp}))))))))

;; ---------------------------------------------------------------------------
;; Idempotent ensures
;; ---------------------------------------------------------------------------

(defn- ensure-database! [session]
  (let [dbs (:data (call! :get "/api/database" {:session session}))
        existing (first (filter #(= xtdb-db-name (:name %)) dbs))]
    (if existing
      (do (log "XTDB datasource present (id" (:id existing) ").") (:id existing))
      (do (log "Creating XTDB read-only datasource.")
          (:id (call! :post "/api/database"
                      {:session session
                       :body {:engine "postgres" :name xtdb-db-name :details xtdb-db-details}}))))))

(defn- ensure-collection! [session]
  (let [resp (call! :get "/api/collection" {:session session})
        cols (or (:data resp) resp)
        existing (first (filter #(= collection-name (:name %)) cols))]
    (if existing
      (do (log "Collection present (id" (:id existing) ").") (:id existing))
      (do (log "Creating collection" collection-name ".")
          (:id (call! :post "/api/collection"
                      {:session session :body {:name collection-name}}))))))

(defn- collection-items [session collection-id]
  (:data (call! :get (str "/api/collection/" collection-id "/items")
                {:session session})))

(defn- tag->parameter
  "Expose a native-SQL template tag as a card parameter so Metabase can pass a
   runtime value (dashboard filters / API override), not just the default."
  [{:keys [type] tag :name}]
  (let [n (str tag)]
    {:name n :slug n :id n :type type
     :target ["variable" ["template-tag" n]]}))

(defn- ensure-card! [session db-id collection-id {:keys [name query tags]}]
  (let [existing (first (filter #(= name (:name %)) (collection-items session collection-id)))
        tags (or tags {})
        params (mapv tag->parameter (vals tags))]
    (if existing
      (let [qid (:id existing)]
        (log "Question present:" name "(id" qid ").")
        (let [card (call! :get (str "/api/card/" qid) {:session session})]
          ;; ensure the card exposes its SQL variables as parameters
          (when (seq params)
            (let [cur (->> (or (:parameters card) [])
                           (mapv #(select-keys % [:id :type :target])))]
              (when-not (= cur (mapv #(select-keys % [:id :type :target]) params))
                (log "Refreshing card parameters:" name)
                (call! :put (str "/api/card/" qid) {:session session :body {:parameters params}}))))
          ;; refresh template-tag defaults (e.g. the live-known-at hero) when the
          ;; seed's correction timestamps changed
          (let [cur-tags (get-in card [:dataset_query :native :template_tags] {})]
            (when (and (seq tags) (not= cur-tags tags))
              (log "Refreshing parameter defaults:" name)
              (call! :put (str "/api/card/" qid)
                     {:session session
                      :body {:dataset_query {:database db-id :type "native"
                                             :native {:query query :template_tags tags}}}}))))
        qid)
      (do (log "Creating question:" name)
          (:id (call! :post "/api/card"
                      {:session session
                       :body {:name name :display "table" :visualization_settings {}
                              :collection_id collection-id :parameters params
                              :dataset_query {:database db-id :type "native"
                                              :native {:query query :template_tags tags}}}}))))))

;; ---------------------------------------------------------------------------
;; Question definitions
;; ---------------------------------------------------------------------------

(def correction-known-at-default
  "Default known-at for the Time Travel hero: the MIDPOINT of the correction
   run's most-recent wrong-version and its following correct-version system
   interval (read live from XTDB). XTDB retains historical versions across
   reseeds, so we target the NEWEST correction cycle; formatted ISO-Z so the
   as-known-then query deterministically returns that cycle's wrong root."
  (try
    (let [ds (xtdb/->datasource)
          rows (jdbc/execute! ds
                              ["SELECT bundle_root, _system_from FROM sim_execution_runs
                                FOR ALL SYSTEM_TIME WHERE _id='run-synthetic-correction'
                                ORDER BY _system_from"])
          wrongs (filter #(str/includes? (str (:bundle_root %)) "INDEX-ERROR") rows)
          wrong (last wrongs)
          ;; the correct version that immediately follows this wrong version
          idx (first (keep-indexed (fn [i r] (when (identical? r wrong) i)) rows))
          correct (some-> (drop (inc idx) rows) first)
          a (.getTime ^java.util.Date (:_system_from wrong))
          b (.getTime ^java.util.Date (:_system_from correct))
          mid (long (+ a (/ (- b a) 2)))]
      (str (java.time.Instant/ofEpochMilli mid)))
    (catch Exception _ nil)))

(defn- txt [tag default] {:type "text" :name tag :display_name (name tag) :default default})

(def questions
  "Native SQL questions grouped by dashboard tab."
  [{:tab "Overview" :name "PRF / Overview / Completed execution count"
    :query "SELECT COUNT(*) AS completed FROM sim_execution_runs"}
   {:tab "Overview" :name "PRF / Overview / Failure count"
    :query "SELECT COUNT(*) AS failed FROM sim_benchmark_executions WHERE outcome IN ('failed','error')"}
   {:tab "Overview" :name "PRF / Overview / Benchmark case count"
    :query "SELECT COUNT(*) AS benchmark_cases FROM sim_benchmark_executions"}
   {:tab "Overview" :name "PRF / Overview / Distinct result roots"
    :query "SELECT COUNT(DISTINCT bundle_root) AS distinct_roots FROM sim_execution_runs"}
   {:tab "Overview" :name "PRF / Overview / Recent executions"
    :query "SELECT _id, scenario_id, bundle_root, status FROM sim_execution_runs ORDER BY _id DESC LIMIT 10"}

   {:tab "Time Travel" :name "PRF / Time Travel / Run best-known-now"
    :query "SELECT _id, scenario_id, bundle_root, status FROM sim_execution_runs WHERE _id = {{run_id}}"
    :tags {"run_id" (txt "run_id" "run-synthetic-correction")}}
   {:tab "Time Travel" :name "PRF / Time Travel / Run as-known-then"
    :query "SELECT _id, bundle_root, _system_from, _system_to FROM sim_execution_runs FOR SYSTEM_TIME AS OF CAST({{known_at}} AS TIMESTAMP WITH TIME ZONE) WHERE _id = {{run_id}}"
    :tags {"run_id" (txt "run_id" "run-synthetic-correction")
           "known_at" (txt "known_at" (or correction-known-at-default "2030-01-01T00:00:00.000Z"))}}
   {:tab "Time Travel" :name "PRF / Time Travel / Run full history"
    :query "SELECT _id, bundle_root, _valid_from, _valid_to, _system_from, _system_to FROM sim_execution_runs FOR ALL SYSTEM_TIME WHERE _id = {{run_id}} ORDER BY _system_from"
    :tags {"run_id" (txt "run_id" "run-synthetic-correction")}}

   {:tab "Failure Archaeology" :name "PRF / Failure / Temporal runs"
    :query "SELECT _id, scenario_id, outcome FROM sim_temporal_runs ORDER BY _id"}
   {:tab "Failure Archaeology" :name "PRF / Failure / Run steps"
    :query "SELECT step_index, action, result, time_after_edn FROM sim_temporal_steps WHERE run_id = {{run_id}} ORDER BY step_index"
    :tags {"run_id" (txt "run_id" "temporal-failure")}}
   {:tab "Failure Archaeology" :name "PRF / Failure / Run invariants"
    :query "SELECT step_index, invariant, holds, severity, violations_edn FROM sim_temporal_invariants WHERE run_id = {{run_id}} ORDER BY step_index"
    :tags {"run_id" (txt "run_id" "temporal-failure")}}

   {:tab "Root Convergence" :name "PRF / Comparison / Executions by result root"
    :query "SELECT _id, scenario_id, bundle_root FROM sim_execution_runs ORDER BY bundle_root, _id"}
   {:tab "Root Convergence" :name "PRF / Comparison / Runs in scenario"
    :query "SELECT _id, scenario_id, bundle_root FROM sim_execution_runs WHERE scenario_id = {{scenario_id}} ORDER BY _id"
    :tags {"scenario_id" (txt "scenario_id" "scenario-divergent")}}])

;; ---------------------------------------------------------------------------
;; Dashboard (v0.58: whole-dashboard PUT)
;; ---------------------------------------------------------------------------

(defn- ensure-dashboard! [session collection-id]
  (let [items (collection-items session collection-id)
        existing (first (filter #(and (= dashboard-name (:name %))
                                      (= "dashboard" (:model %)))
                                items))]
    (if existing
      (do (log "Dashboard present (id" (:id existing) ").") (:id existing))
      (do (log "Creating dashboard" dashboard-name ".")
          (:id (call! :post "/api/dashboard"
                      {:session session :body {:name dashboard-name :collection_id collection-id}}))))))

(defn- dashboard-detail [session dashboard-id]
  (call! :get (str "/api/dashboard/" dashboard-id) {:session session}))

(defn- ensure-tabs!
  "Ensure the 4 named tabs exist. Returns {tab-name -> tab-id}."
  [session dashboard-id]
  (let [dash (dashboard-detail session dashboard-id)
        tabs (vec (:tabs dash))
        want ["Overview" "Time Travel" "Failure Archaeology" "Root Convergence"]
        missing (vec (remove (set (map :name tabs)) want))]
    (if (seq missing)
      (let [;; new tabs need distinct negative ids (Metabase requires unique ids)
            new-tabs (mapv (fn [i tn] {:id (- (inc i)) :name tn}) (range) missing)
            updated (call! :put (str "/api/dashboard/" dashboard-id)
                           {:session session
                            :body {:name dashboard-name
                                   :tabs (into (mapv (fn [t] {:id (:id t) :name (:name t)}) tabs) new-tabs)
                                   :dashcards (vec (mapv (fn [dc] {:id (:id dc) :card_id (:card_id dc)
                                                                   :dashboard_tab_id (:dashboard_tab_id dc)
                                                                   :row (:row dc) :col (:col dc)
                                                                   :size_x (:size_x dc) :size_y (:size_y dc)})
                                                         (:dashcards dash)))}})
            _ (log "Ensured tabs.")]
        (into {} (map (juxt :name :id) (:tabs updated))))
      (into {} (map (juxt :name :id) tabs)))))

(defn- ensure-dashboard-cards!
  "Add each saved question to its tab, idempotently."
  [session dashboard-id tab-ids question-ids]
  (let [dash (dashboard-detail session dashboard-id)
        existing-cards (set (map :card_id (:dashcards dash)))
        card-by-tab (group-by :tab questions)
        additions
        (->> card-by-tab
             (mapcat (fn [[tab qs]]
                       (let [tab-id (get tab-ids tab)]
                         (map-indexed (fn [i q]
                                        (when-not (contains? existing-cards (get question-ids (:name q)))
                                          {:card_id (get question-ids (:name q))
                                           :dashboard_tab_id tab-id
                                           :row (* i 8) :col 0 :size_x 18 :size_y 8
                                           :parameter_mappings []}))
                                      qs))))
             (remove nil?)
             (map-indexed (fn [i c] (assoc c :id (- (inc i)))))
             vec)]
    (when (seq additions)
      (let [_updated (call! :put (str "/api/dashboard/" dashboard-id)
                           {:session session
                            :body {:name dashboard-name
                                   :tabs (mapv (fn [[n id]] {:id id :name n}) tab-ids)
                                   :dashcards (into (vec (mapv (fn [dc] {:id (:id dc) :card_id (:card_id dc)
                                                                          :dashboard_tab_id (:dashboard_tab_id dc)
                                                                          :row (:row dc) :col (:col dc)
                                                                          :size_x (:size_x dc) :size_y (:size_y dc)})
                                                              (:dashcards dash)))
                                                    additions)}})]
        (log "Added" (count additions) "dashboard cards.")))
    (let [dash-after (dashboard-detail session dashboard-id)]
      (count (:dashcards dash-after)))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn- ensure-disclaimer-cards!
  "Add a persistent 'Derived evidence index' text card to each tab (idempotent).
   This is the standing disclaimer that the rows are non-authoritative projections."
  [session dashboard-id tab-ids]
  (let [dash (dashboard-detail session dashboard-id)
        existing-text-tabs (set (map :dashboard_tab_id (filter #(nil? (:card_id %)) (:dashcards dash))))
        additions (->> tab-ids
                       (map-indexed (fn [i [_tab-name tab-id]]
                                      (when-not (contains? existing-text-tabs tab-id)
                                        {:id (- (inc i)) :card_id nil :dashboard_tab_id tab-id
                                         :row 0 :col 0 :size_x 18 :size_y 4
                                         :visualization_settings {:text disclaimer-text}
                                         :parameter_mappings []})))
                       (remove nil?)
                       vec)]
    (when (seq additions)
      (call! :put (str "/api/dashboard/" dashboard-id)
             {:session session
              :body {:name dashboard-name
                     :tabs (mapv (fn [[n id]] {:id id :name n}) tab-ids)
                     :dashcards (into (vec (mapv (fn [dc] {:id (:id dc) :card_id (:card_id dc)
                                                            :dashboard_tab_id (:dashboard_tab_id dc)
                                                            :row (:row dc) :col (:col dc)
                                                            :size_x (:size_x dc) :size_y (:size_y dc)
                                                            :visualization_settings (:visualization_settings dc)})
                                                (:dashcards dash)))
                                      additions)}})
      (log "Added" (count additions) "disclaimer text card(s)."))))

(defn- -provision! []
  (log "== PRF / XTDB Explorer provisioning ==")
  (let [session (ensure-admin!)
        db-id   (ensure-database! session)
        col-id  (ensure-collection! session)
        _ (log "Using XTDB datasource id" db-id "collection" col-id)
        question-ids (into {} (map (fn [q] [(:name q) (ensure-card! session db-id col-id q)]) questions))
        dash-id (ensure-dashboard! session col-id)
        tab-ids (ensure-tabs! session dash-id)
        ncards  (ensure-dashboard-cards! session dash-id tab-ids question-ids)]
    (ensure-disclaimer-cards! session dash-id tab-ids)
    (log "Dashboard" dash-id "tabs" (count tab-ids) "cards" ncards)
    (str base-url "/dashboard/" dash-id)))

(defn- run-card [session card-id]
  (call! :post (str "/api/card/" card-id "/query")
         {:session session :body {:parameters []}}))

(defn- find-card-by-name [session col-id name]
  (:id (first (filter #(= name (:name %)) (collection-items session col-id)))))

(defn check!
  "Read-only contract smoke: assert the provisioned External Explorer holds.
   Establishes datasource + collection + dashboard (4 tabs / 17 cards) and that
   the hero temporal answers are correct (current=8, history>=2, as-known-then=
   WRONG-ROOT, best-known-now=correct-root)."
  []
  (let [session (ensure-admin!)
        dbs (:data (call! :get "/api/database" {:session session}))
        _ (assert (some #(= xtdb-db-name (:name %)) dbs) "XTDB datasource missing")
        col-resp (call! :get "/api/collection" {:session session})
        col (first (filter #(= collection-name (:name %)) (or (:data col-resp) col-resp)))
        _ (assert col "PRF / XTDB Explorer collection missing")
        col-id (:id col)
        items (collection-items session col-id)
        dash (first (filter #(and (= dashboard-name (:name %)) (= "dashboard" (:model %))) items))
        _ (assert dash "dashboard missing")
        detail (dashboard-detail session (:id dash))
        _ (assert (= 4 (count (:tabs detail))) (str "expected 4 tabs, got " (count (:tabs detail))))
        _ (assert (= 17 (count (:dashcards detail))) (str "expected 17 cards, got " (count (:dashcards detail))))
        cur-rows (get-in (run-card session (find-card-by-name session col-id "PRF / Overview / Completed execution count"))
                         [:data :rows 0 0])
        _ (assert (= 8 cur-rows) (str "expected 8 runs, got " cur-rows))
        fh-rows (get-in (run-card session (find-card-by-name session col-id "PRF / Time Travel / Run full history"))
                        [:data :rows])
        _ (assert (>= (count fh-rows) 2) "expected >=2 correction history versions")
        ak-root (get-in (run-card session (find-card-by-name session col-id "PRF / Time Travel / Run as-known-then"))
                        [:data :rows 0 1])
        _ (assert (str/includes? (str ak-root) "INDEX-ERROR") (str "expected as-known-then wrong root, got " ak-root))
        now-root (get-in (run-card session (find-card-by-name session col-id "PRF / Time Travel / Run best-known-now"))
                         [:data :rows 0 2])
        _ (assert (str/includes? (str now-root) "result-correct") (str "expected best-known-now correct root, got " now-root))]
    (println "Metabase External Explorer contract OK:")
    (println "  datasource / collection / dashboard (4 tabs, 17 cards): OK")
    (println "  current runs:" cur-rows)
    (println "  correction history versions:" (count fh-rows))
    (println "  as-known-then ->" ak-root)
    (println "  best-known-now ->" now-root)))

(defn -main [& args]
  (if (= "check" (first args))
    (check!)
    (try
      (let [url (-provision!)]
        (println)
        (println "PRF / XTDB Explorer (Metabase):")
        (println url)
        (println "login:" admin-email "password:" admin-password))
      (catch Throwable e
        (println "Provisioning failed:" (.getMessage e))
        (when-let [d (ex-data e)] (prn d))
        (.printStackTrace e)
        (System/exit 1)))))