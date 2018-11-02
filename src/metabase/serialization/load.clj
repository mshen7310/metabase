(ns metabase.serialization.load
  ""
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]
            [metabase.automagic-dashboards.filters :refer [field-reference?]]
            [metabase.models
             [card :refer [Card]]
             [collection :refer [Collection]]
             [dashboard :refer [Dashboard]]
             [dashboard-card :refer [DashboardCard]]
             [database :refer [Database]]
             [field :refer [Field]]
             [metric :refer [Metric]]
             [segment :refer [Segment]]
             [table :refer [Table]]
             [user :refer [User]]]
            [metabase.query-processor.util :as qp.util]
            [metabase.util :as u]
            [metabase.util.schema :as su]
            [schema.core :as s]
            [toucan.db :as db]
            [yaml.core :as yaml])
  (:refer-clojure :exclude [load]))

(defn- slurp-dir
  [f path]
  (->> path
       io/file
       file-seq
       (filter #(.isFile %))
       (map (fn [file]
              (let [entity (yaml/from-file file)]
                {(:id entity) (:id (f (dissoc entity :id)))})))
       (apply merge)))

(defn- fully-qualified-name->id
  [[db schema table field]]
  (let [db    (db/select-one Database :name db)
        table (db/select-one Table
                :db_id  (u/get-id db)
                :schema schema
                :name   table)]
    (db/select-one-field :id Field
      :name     field
      :table_id (u/get-id table))))

(defn- fully-qualified-name->field-reference
  [[op & args]]
  (into [op] (map (fn [arg]
                    (if (sequential? arg)
                      (fully-qualified-name->id arg)
                      arg))
                  args)))

(def ^:private EntityReference
  [(s/one (s/constrained su/KeywordOrString
                         (comp #{:metric :segment} qp.util/normalize-token))
          "head")
   (s/cond-pre s/Int su/KeywordOrString)])

(def ^{:arglists '([form])} entity-reference?
  "Is given form an MBQL entity reference (metric or segment)?"
  (complement (s/checker EntityReference)))

(defn- update-entity-reference-id
  [[op id] context ]
  (let [op (qp.util/normalize-token op)]
    [op (if (= op :metric)
          ((:metrics context) id id)
          ((:segmentes context) id id))]))

(defn- humanized-field-references->ids
  [entity context]
  (walk/postwalk (fn [form]
                   (cond
                     (field-reference? form)  (fully-qualified-name->field-reference form)
                     (entity-reference? form) (update-entity-reference-id form context)
                     :else                    form)))
                 entity)

(defmulti
  ^{:doc      ""
    :private  true
    :arglists '([dir model context])}
  load (fn [_ model _]
         model))

(defmethod load Database
  [path _ context]
  (let [context (assoc context
                  :databases (slurp-dir (partial db/insert! Database) (str path "/databases")))]
    (reduce (fn [context db]
              (load (format "%s/databases/%s" path db) Table context))
            context
            (db/select-field :name Database [:in :id (-> context :databases vals)]))))

(defmethod load Table
  [path _ context]
  (let [context (assoc context
                  :tables (slurp-dir (fn [table]
                                       (db/insert! Table
                                         (update table :db_id (:databases context))))
                                     (str path "/tables")))]
    (reduce (fn [context table]
              (let [path (format "%s/tables/%s" path table)]
                (->> context
                     (load (str path "/fields") Field)
                     (load (str path "/metrics") Metric)
                     (load (str path "/segments") Segment))))
            context
            (db/select-field :name Table [:in :id (-> context :tables vals)]))))

(defmethod load Field
  [path _ context]
  (assoc context
    :fields (slurp-dir (fn [field]
                         (db/insert! Field
                           (update field :table_id (:tables context))))
                       (str path "/fields"))))

(defmethod load Metric
  [path _ context]
  (assoc context
    :metrics (slurp-dir (fn [metric]
                          (db/insert! Metric
                            (-> metric
                                (update :table_id (:tables context))
                                (update :creator_id (:users context))
                                (update-in [:definition :source-table] (:tables context))
                                (humanized-field-references->ids context))))
                        (str path "/metrics"))))

(defmethod load Segment
  [path _ context]
  (assoc context
    :segments (slurp-dir (fn [segment]
                           (db/insert! Segment
                             (-> segment
                                 (update :table_id (:tables context))
                                 (update :creator_id (:users context))
                                 (update-in [:definition :source-table] (:tables context))
                                 (humanized-field-references->ids context))))
                         (str path "/segments"))))

(defmethod load User
  [path _ context]
  (assoc context
    :users (slurp-dir (fn [user]
                        (if (db/exists? User :email (:email user))
                          {(:id user) (:id user)}
                          (db/insert! User user)))
                      (str path "/users"))))

(defmethod load Dashboard
  [path _ context]
  (let [context (assoc context
                  :dashbboards (slurp-dir (fn [dashbboard]
                                            (db/insert! Dashboard
                                              (-> dashbboard
                                                  (update :collection_id (:collections context))
                                                  (update :creator_id (:users context))
                                                  (humanized-field-references->ids context))))
                                          path))]
    (reduce (fn [context db]
              (load (format "%s/dashboards/%s" path db) DashboardCard context))
            context
            (db/select-field :name Dashboard [:in :id (-> context :dashboards vals)]))))

(defmethod load Card
  [path _ context]
  (assoc context
    :cards (slurp-dir (fn [card]
                        (db/insert! Card
                          (-> card
                              (update :table_id (:tables context))
                              (update :creator_id (:users context))
                              (update :database_id (:databases context))
                              (update-in [:dataset_query :database] (:databases context))
                              (cond->
                                  (-> card :dataset_query :type qp.util/normalize-token (= :query))
                                (update-in [:dataset_query :query :source-table] (:tables context)))
                              (humanized-field-references->ids context))))
                      (str path "/cards"))))

(defn- update-parameter-mappings
  [parameter-mappings context]
  (map #(update % :card_id (:cards context)) parameter-mappings))

(defmethod load DashboardCard
  [path _ context]
  (assoc context
    :dashboard-cards (slurp-dir (fn [dashboard-card]
                                  (db/insert! DashboardCard
                                    (-> dashboard-card
                                        (update :card_id (:card context))
                                        (update :dashboard_id (:dashboards context))
                                        (update :parameter-mappings update-parameter-mappings)
                                        (humanized-field-references->ids context))))
                                (str path "/dashboard-cards"))))

(defmethod load Collection
  [path _ context]
  (assoc context :collections (slurp-dir (fn [collection]
                                           (db/insert! Collection
                                             (update collection :personal_owner_id (:users context))))
                                         (str path "/collections"))))

(defn -main
  [path & _]
  (->> {}
       (load path Database)
       (load path User)
       (load path Collection)
       (load path Card)
       (load path Dashboard)))