(ns factui.rules
  (:require [factui.txdata :as txdata]
            [factui.facts :as f]
            [clara.rules :as c.r]
            [clara.rules.accumulators :as acc]
            [clojure.spec.alpha :as s]
            [clojure.walk :as w]))

(s/def ::condition
  (s/or :boolean-expr ::boolean-expr
        :accum-expr ::accum-expr
        :fact-constraint ::fact-constraint
        :test-expr ::test-expr))

(s/def ::fact-constraint (s/or :clara-constraint ::clara-constraint
                               :datalog-constraint ::datalog-constraint))

(s/def ::boolean-expr (s/tuple #{:and :or :not} (s/or ::boolean-expr
                                                      ::fact-constraint)))

(s/def ::test-expr (s/tuple #{:test} ::s-expression))

(s/def ::s-expression list?)

(s/def ::accum-expr (s/tuple ::binding #{'<-} ::accumulator #{:from} ::fact-constraint))

(s/def ::binding-expr (s/cat :binding ::binding :arrow-symbol #{'<-}))

(s/def ::destructured-fact vector?)

(s/def ::clara-constraint (s/and vector?
                            (s/cat :binding-expr (s/? ::binding-expr)
                                   :fact-type symbol?
                                   :destructured-fact (s/? ::destructured-fact)
                                   :s-expressions (s/* ::s-expression))))

(s/def ::datalog-constraint (s/tuple any? any? any?))

(s/def ::binding symbol?)
(s/def ::accumulator ifn?)

(s/def ::defrule-args (s/cat :name symbol?
                        :docstring (s/? string?)
                        :props (s/? map?)
                        :conditions (s/+ ::condition)
                        :separator #{'=>}
                        :rhs any?))

(s/def ::defquery-args (s/cat :name symbol?
                              :docstring (s/? string?)
                              :argvec (s/coll-of keyword?)
                              :conditions (s/+ ::condition)))

(s/fdef defrule :args ::defrule-args)
(s/fdef defquery :args ::defquery-args)

(defn- convert-constraint
  "Convert a conformed Datalog-style constraint to a conformed Clara-style
   constraint"
  [[e a v]]
  (let [e-expr (when (and e (not= e '_)) `(= ~'e ~e))
        a-expr (when (and a (not= a '_)) `(= ~'a ~a))
        v-expr (when (and v (not= v '_)) `(= ~'v ~v))
        exprs (vec (remove nil? [e-expr a-expr v-expr]))]
    {:fact-type 'factui.facts.Datom
     :s-expressions exprs}))

(defn swap-datalog-conditions
  "Walk conformed arguments to defrule, replacing any datalog conditions with
   regular Clara conditions."
  [args]
  (w/postwalk (fn [form]
                (if (and (vector? form) (= :datalog-constraint (first form)))
                  [:clara-constraint (convert-constraint (second form))]
                  form))
    args))

#?(:clj
   (defmacro defrule
     "Exactly the same as Clara's defrule, with the exception that
      Datalog-style binding tuples are allowed as conditions."
     [& args]
     (let [conformed-args (s/conform ::defrule-args args)
           swapped-args (swap-datalog-conditions conformed-args)
           unformed-args (s/unform ::defrule-args swapped-args)]
       `(c.r/defrule ~@unformed-args))))

#?(:clj
  (defmacro defquery
    "Exactly the same as Clara's defquery, with the exception that
      Datalog-style binding tuples are allowed as conditions."
    [& args]
    (let [conformed-args (s/conform ::defquery-args args)
          swapped-args (swap-datalog-conditions conformed-args)
          unformed-args (s/unform ::defquery-args swapped-args)]
      `(c.r/defquery ~@unformed-args))))


(let [eid (atom 1)]
  (defn new-eid
    "Return a new, unique EID"
    []
    (swap! eid inc)))

(defn entity
  "Returns an accumulator that builds an entity map from a set of datoms.

  Currently extremely naive: doesn't handle cardinality many attributes, or
  handle retraction properly (although these could be added)"
  []
  (acc/accum
    {:initial-value {}
     :reduce-fn (fn [entity {:keys [e a v]}]
                  (assoc entity a v))}))

;;;;;;;;;;;;;;;;;;; Rules to implement Datomic features

(def ^:dynamic *tempid-bindings*)
(def ^:dynamic *bootstrap* false)

(c.r/defrule clean-up-transactions-rule
  "As the final rule, clean up by retracting all remaining transactional
   facts, as they are not intended to be a permanent part of the fact
   store."
  {:salience -100}
  [?facts <- (acc/all) :from [factui.facts.Transactional]]
  =>
  (apply c.r/retract! ?facts))

(c.r/defrule enforce-schema-rule
  "Thrown an exception when trying to add an attribute not in the schema"
  [?op <- factui.facts.Operation (= op ?op) (= ?a (second args))]
  [:test (contains? #{:db/add :db/retract} ?op)]
  [:not [factui.facts.Attribute (= ident ?a)]]
  =>
  (when-not *bootstrap*
    (throw (ex-info (str "Unknown attribute " ?a) {:attr ?a
                                                   :operation ?op}))))

(c.r/defrule missing-entity-rule
  "Insertions to a concrete entity ID not present in the DB should blow up"
  [factui.facts.Operation (= op :db/add) (= ?e (first args))]
  [:test (pos-int? ?e)]
  [:not [factui.facts.Datom (= e ?e)]]
  =>
  (throw (ex-info "Entity does not exist" {:eid ?e})))

(c.r/defrule insertions-rule
  "Monster rule to handle all :db/add operations, in all their various
   flavors."
  {:salience -10}
  [:or
   ;; tempid case
   [:and
    [?op <- factui.facts.Operation [{op :op [e a v] :args}] (= op :db/add) (= ?tid e) (= ?a a) (= ?v v)]
    [:test (not (pos-int? ?tid))]
    [factui.facts.TempidBinding (= tempid ?tid) (= ?e eid)]]
   ;; concrete eid case
   [:and
    [?op <- factui.facts.Operation [{op :op [e a v] :args}] (= op :db/add) (= ?e e) (= ?a a) (= ?v v)]
    [factui.facts.Datom (= e ?e)]
    ]]
  [:not [factui.facts.Datom (= e ?e) (= a ?a) (= v ?v)]]
  [:or
   ;; Cardinality many
   [:not [factui.facts.Attribute (= ident ?a) (= true card-one?)]]
   ;; Cardinality one without old value
   [factui.facts.Attribute (= ident ?a) (= true card-one?)]
   ;; Cardinality one with old value
   [:and
    [factui.facts.Attribute (= ident ?a) (= true card-one?)]
    [?old <- factui.facts.Datom (= e ?e) (= a ?a)]]]
  =>
  (c.r/retract! ?op)
  (when ?old
    (c.r/retract! ?old))
  (c.r/insert-unconditional! (f/->Datom ?e ?a ?v)))

(c.r/defrule assign-tempid-rule
  "Create a tempid binding for an unconstrainted tempid"
  [?ops <- (acc/all) :from [factui.facts.Operation (= op :db/add) (= ?tid (first args))]]
  [:not [factui.facts.TempidBinding (= tempid ?tid)]]
  [:test (not (pos-int? ?tid))]
  =>
  (let [eid (new-eid)]
    (swap! *tempid-bindings* assoc ?tid eid)
    (c.r/insert-unconditional! (f/->TempidBinding ?tid eid))))

(c.r/defrule assign-identity-tempid-rule
  "Create a tempid binding for an ident attr"
  {:salience 100}
  [factui.facts.Operation [{op :op [e a v] :args}] (= op :db/add) (= ?tid e) (= ?a a) (= ?v v)]
  [:test (not (pos-int? ?tid))]
  [:not [factui.facts.TempidBinding (= tempid ?tid)]]
  [factui.facts.Attribute (= ident ?attr) (= true identity?)]
  [factui.facts.Datom (= e ?eid) (= a ?attr) (= v ?v)]
  =>
  (swap! *tempid-bindings* assoc ?tid ?eid)
  (c.r/insert-unconditional! (f/->TempidBinding ?tid ?eid)))

(c.r/defrule schema-insertion-rule
  "Adds attribute facts to the DB when schema txdata is transacted"
  [factui.facts.Datom (= e ?e) (= a :db/valueType)]
  [?entity <- (entity) :from [factui.facts.Datom (= e ?e)]]
  =>
  (c.r/insert-unconditional!
    (f/map->Attribute
      {:ident (:db/ident ?entity)
       :type (:db/valueType ?entity)
       :card-one? (not= :db.cardinality/many (:db/cardinality ?entity))
       :identity? (= :db.unique/identity (:db/unique ?entity))})))

;;;;;;;;;;;;;;;;;;


(c.r/defquery everything
  "Return everything in the session."
  []
  [?fact <- java.util.Map])


(c.r/defquery datoms
  "Return everything in the session."
  []
  [?fact <- factui.facts.Datom])

(c.r/defquery find-entity
  "Return all datoms relating to an entity"
  [:?eid]
  [?fact <- factui.facts.Datom (= e ?eid)])

(use 'clojure.pprint)

(defn transact
  "Add Datomic-style transaction data to the session, returning a tuple of the
   new session and a map of the tempid bindings."
  [session txdata]
  (binding [*tempid-bindings* (atom {})]
    (let [facts (txdata/txdata txdata)
          new-session (c.r/fire-rules (c.r/insert-all session facts))]
      [new-session @*tempid-bindings*])))

(defn transact-all
  "Apply multiple transactions sequentially, returning the updated session."
  [session & txes]
  (reduce (fn [s tx]
            (first (transact s tx)))
    session txes))

(def bootstrap-schema
  "Initial schema for the FactUI fact base"
  [{:db/ident :db/valueType
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/keyword}
   {:db/ident :db/unique
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/keyword}
   {:db/ident :db/ident
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/valueType :db.type/keyword}
   {:db/ident :db/cardinality
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/keyword}
   {:db/ident :db/cardinality
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/keyword}
   {:db/ident :db/doc
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/string}
   {:db/ident :db/doc
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/string}
   {:db/ident :db/isComponent
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/boolean}
   ;; Compatibility purposes only
   {:db/ident :db/index
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/boolean}
   {:db/ident :db/fulltext
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/boolean}
   {:db/ident :db/noHistory
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/boolean}])

(defmacro defsession
  "Wrapper for Clara's `defsession`, which takes the same arguments.

  Automatically handles adding the FactUI schema & rules."
  [name & args]
  (let [original-name (gensym)]
    `(do
       (c.r/defsession ~original-name 'factui.rules ~@args)
       (binding [*bootstrap* true]
         (def ~name
           (first (transact ~original-name bootstrap-schema)))))))

(defquery people
  "doc"
  []
  [?p :person/id _]
  [?p ?a ?v])

(comment
  (require '[clara.tools.inspect :as ins])
  (use 'clojure.pprint)

  (do

    (println "\n")
    (defsession sess)

    (def sess' (transact-all sess
                 [{:db/ident :person/id
                   :db/valueType :db.type/long
                   :db/cardinality :db.cardinality/one
                   :db/unique :db.unique/identity}
                  {:db/ident :person/name
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}
                  {:db/ident :person/likes
                   :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/many}]
                 [{:person/id 42
                   :person/name "Luke"
                   :person/likes #{"Beer" "Cheese"}}]
                 [{:person/id 43
                   :person/name "Chad"}]
                 [{:person/id 42
                   :person/likes "Meat"}]

                 ))



    (pprint
      (c.r/query sess' people))

    )



  )






    ;; TODO: Build txdata interface (DONE)
    ;; TODO: Build rules to enforce key Datomic semantics (IN PROGRESS)
    ;; - No new entity IDs (DONE)
    ;; - Cardinality 1 (DONE)
    ;; - Tempids & upsert with db/identity (DONE)
    ;; - Tx-functions (mechanism in place)
    ;; TODO: Build more idiomatic query API (with :find clause & aggs)
    ;; TODO: Do some benchmarks