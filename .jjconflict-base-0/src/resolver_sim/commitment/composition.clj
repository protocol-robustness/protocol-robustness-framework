(ns resolver-sim.commitment.composition
  "Canonical typed commitment composition.

   A composition binds independently meaningful SHA-256 references into one
   higher-order root without erasing each member's semantic role. It is not a
   byte-concatenation convention and it does not provide confidentiality:
   delayed disclosure belongs to a separate sealing protocol."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def schema-version "prf/commitment-composition.v1")
(def composition-domain :prf-commitment-composition-v1)

(def ^:private member-keys
  #{:member/type :member/name :member/commitment})

(def ^:private composition-keys
  #{:artifact/schema :composition/domain :composition/members :composition/root})

(defn- member-order-key [{:member/keys [type name]}]
  [(str type) (str name)])

(defn- canonical-members [members]
  (vec (sort-by member-order-key members)))

(defn- member-errors [member]
  (cond-> []
    (not (map? member)) (conj :commitment-composition/member-not-map)
    (and (map? member) (not= member-keys (set (keys member))))
    (conj :commitment-composition/member-keys-invalid)
    (and (map? member) (not (keyword? (:member/type member))))
    (conj :commitment-composition/member-type-invalid)
    (and (map? member) (not (keyword? (:member/name member))))
    (conj :commitment-composition/member-name-invalid)
    (and (map? member) (not (hash-ref/valid-sha256-ref? (:member/commitment member))))
    (conj :commitment-composition/member-commitment-invalid)))

(defn composition-preimage
  "Return the exact canonical V1 root preimage.

   `:composition/domain` identifies the semantic use of this composition. The
   registered outer domain identifies the composition artifact construction
   itself. Members are sorted by the lexical keyword renderings of their type
   and semantic name; member names must be unique, so a root cannot move from
   one named role to another without changing the root."
  [{:composition/keys [domain members]}]
  {:artifact/schema schema-version
   :composition/domain domain
   :composition/members members})

(defn composition-root
  "Return the strict sha256 reference for a valid composition preimage."
  [composition]
  (hash-ref/sha256-ref
   (hc/domain-hash composition-domain (composition-preimage composition))))

(defn validate-composition
  "Validate a fully materialized `prf/commitment-composition.v1` artifact.

   Returns `{:valid? boolean :errors [keyword ...]}`. Validation is strict:
   members must be an already canonical vector, each member has exactly the
   V1 keys, names are unique, references are canonical `sha256:` strings, and
   the supplied self-root must match the canonical preimage."
  [composition]
  (let [members (:composition/members composition)
        member-errors* (if (vector? members)
                         (mapcat member-errors members)
                         [])
        names (when (vector? members) (map :member/name members))
        expected-root (when (and (map? composition)
                                 (= schema-version (:artifact/schema composition))
                                 (keyword? (:composition/domain composition))
                                 (vector? members)
                                 (empty? member-errors*)
                                 (seq members)
                                 (= (count names) (count (distinct names)))
                                 (= members (canonical-members members)))
                        (composition-root composition))
        errors (cond-> []
                 (not (map? composition))
                 (conj :commitment-composition/not-a-map)
                 (and (map? composition) (not= composition-keys (set (keys composition))))
                 (conj :commitment-composition/composition-keys-invalid)
                 (and (map? composition) (not= schema-version (:artifact/schema composition)))
                 (conj :commitment-composition/unsupported-schema-version)
                 (and (map? composition) (not (keyword? (:composition/domain composition))))
                 (conj :commitment-composition/domain-invalid)
                 (not (vector? members))
                 (conj :commitment-composition/members-not-vector)
                 (and (vector? members) (empty? members))
                 (conj :commitment-composition/members-empty)
                 (and (vector? members) (not= members (canonical-members members)))
                 (conj :commitment-composition/members-not-canonically-ordered)
                 (and (vector? members) (not= (count names) (count (distinct names))))
                 (conj :commitment-composition/duplicate-member-name)
                 (seq member-errors*)
                 (into member-errors*)
                 (and (map? composition) (not (hash-ref/valid-sha256-ref? (:composition/root composition))))
                 (conj :commitment-composition/root-invalid)
                 (and expected-root (not= expected-root (:composition/root composition)))
                 (conj :commitment-composition/root-mismatch))]
    {:valid? (empty? errors)
     :errors (vec errors)}))

(defn valid-composition? [composition]
  (:valid? (validate-composition composition)))

(defn build-composition
  "Build a canonical typed commitment composition.

   Input is `{:composition/domain keyword :composition/members vector}`. The
   builder orders members deterministically but refuses malformed or duplicate
   semantic names rather than coercing ambiguous representations."
  [{:composition/keys [domain members] :as input}]
  (when-not (= #{:composition/domain :composition/members} (set (keys input)))
    (throw (ex-info "commitment composition input has an invalid key set"
                    {:expected #{:composition/domain :composition/members}
                     :actual (set (keys input))})))
  (when-not (keyword? domain)
    (throw (ex-info "commitment composition requires a keyword :composition/domain"
                    {:domain domain})))
  (when-not (vector? members)
    (throw (ex-info "commitment composition requires a vector :composition/members"
                    {:members-type (some-> members class .getName)})))
  (when (empty? members)
    (throw (ex-info "commitment composition requires at least one member" {})))
  (let [member-errors* (vec (mapcat member-errors members))
        names (map :member/name members)]
    (when (seq member-errors*)
      (throw (ex-info "commitment composition contains an invalid member"
                      {:errors member-errors*})))
    (when-not (= (count names) (count (distinct names)))
      (throw (ex-info "commitment composition requires unique member names"
                      {:names (vec names)})))
    (let [composition (assoc (composition-preimage {:composition/domain domain
                                                    :composition/members (canonical-members members)})
                             :composition/root nil)
          root (composition-root composition)]
      (assoc composition :composition/root root))))
