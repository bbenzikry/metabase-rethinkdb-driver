(ns metabase.driver.rethinkdb
  (:require [cheshire
             [core :as json]
             [generate :as json.generate]]
            [clojure.tools.logging :as log]
            [metabase
              [util :as u]]
            [metabase.driver.common :as driver.common]
            [metabase.db.metadata-queries :as metadata-queries]
            [metabase.driver :as driver]
            [metabase.query-processor.store :as qp.store]
            [metabase.driver.rethinkdb
              [util :refer [*-to-clj-rethinkdb-params]]]
            [schema.core :as s]
            [rethinkdb.query :as r]))

(driver/register! :rethinkdb)

(defmethod driver/supports? [:rethinkdb :basic-aggregations] [_ _] true)
(defmethod driver/supports? [:rethinkdb :nested-fields]      [_ _] true)

(defmethod driver/can-connect? :rethinkdb
  [_ details]
  (log/info (format "driver/can-connect? details: %s" details))
  (let [clj-rethinkdb-params (*-to-clj-rethinkdb-params details)]
    (with-open [conn (apply r/connect (mapcat identity clj-rethinkdb-params))]
      (-> (r/now) (r/gt 0) (r/run conn)))))

(defmethod driver/describe-database :rethinkdb
  [driver database]
  (log/info (format "driver/describe-database: driver=%s database=%s" driver database))
  (let [clj-rethinkdb-params (*-to-clj-rethinkdb-params database)]
    (with-open [conn (apply r/connect (mapcat identity clj-rethinkdb-params))]
      {:tables
        (set
          (for [table-name (-> (r/table-list) (r/run conn))]
            {:name table-name
            :schema nil}))})))

(defn- val->special-type [field-value]
  (cond
    ;; 1. url?
    (and (string? field-value)
         (u/url? field-value))
    :type/URL

    ;; 2. json?
    (and (string? field-value)
         (or (.startsWith "{" field-value)
             (.startsWith "[" field-value)))
    (when-let [j (u/ignore-exceptions (json/parse-string field-value))]
      (when (or (map? j)
                (sequential? j))
        :type/SerializedJSON))))

(declare update-field-attrs)

(defn- find-nested-fields [field-value nested-fields]
  (loop [[k & more-keys] (keys field-value)
         fields nested-fields]
    (if-not k
      fields
      (recur more-keys (update fields k (partial update-field-attrs (k field-value)))))))

(defn- update-field-attrs [field-value field-def]
  (-> field-def
      (update :count u/safe-inc)
      (update :len #(if (string? field-value)
                      (+ (or % 0) (count field-value))
                      %))
      (update :types (fn [types]
                       (update types (type field-value) u/safe-inc)))
      (update :special-types (fn [special-types]
                               (if-let [st (val->special-type field-value)]
                                 (update special-types st u/safe-inc)
                                 special-types)))
      (update :nested-fields (fn [nested-fields]
                               (if (map? field-value)
                                 (find-nested-fields field-value nested-fields)
                                 nested-fields)))))
;; TODO: nested field support

(defn- table-sample-column-info
  "Sample the rows (i.e., documents) in `table` and return a map of information about the column keys we found in that
   sample. The results will look something like:

      {:_id      {:count 200, :len nil, :types {java.lang.Long 200}, :special-types nil, :nested-fields nil},
       :severity {:count 200, :len nil, :types {java.lang.Long 200}, :special-types nil, :nested-fields nil}}"
  [^rethinkdb.core.Connection conn, table]
  (let [sample-rows (-> (:name table)
                        (r/table)
                        (r/sample metadata-queries/max-sample-rows)
                        (r/run conn))]
    (log/info (format "table-sample-column-info: sample-rows=%s" sample-rows))
    (reduce
      (fn [field-defs row]
        (loop [[k & more-keys] (keys row), fields field-defs]
          (if-not k
            fields
            (recur more-keys (update fields k (partial update-field-attrs (k row)))))))
    {} sample-rows)))

(s/defn ^:private ^Class most-common-object-type :- (s/maybe Class)
  "Given a sequence of tuples like [Class <number-of-occurances>] return the Class with the highest number of
  occurances. The basic idea here is to take a sample of values for a Field and then determine the most common type
  for its values, and use that as the Metabase base type. For example if we have a Field called `zip_code` and it's a
  number 90% of the time and a string the other 10%, we'll just call it a `:type/Number`."
  [field-types :- [(s/pair (s/maybe Class) "Class", s/Int "Int")]]
  (->> field-types
       (sort-by second)
       last
       first))

(defn- describe-table-field [field-kw field-info]
  (let [most-common-object-type (most-common-object-type (vec (:types field-info)))]
    (cond-> {:name          (name field-kw)
             :database-type (some-> most-common-object-type .getName)
             :base-type     (driver.common/class->base-type most-common-object-type)}
      (= :_id field-kw)           (assoc :pk? true)
      (:special-types field-info) (assoc :special-type (->> (vec (:special-types field-info))
                                                            (filter #(some? (first %)))
                                                            (sort-by second)
                                                            last
                                                            first))
      (:nested-fields field-info) (assoc :nested-fields (set (for [field (keys (:nested-fields field-info))]
                                                               (describe-table-field field (field (:nested-fields field-info)))))))))

(defmethod driver/describe-table :rethinkdb [_ database table]
  (log/info (format "driver/describe-table: database=%s table=%s" database table))
  (let [clj-rethinkdb-params (*-to-clj-rethinkdb-params database)]
    (with-open [conn (apply r/connect (mapcat identity clj-rethinkdb-params))]
      (let [column-info (table-sample-column-info conn table)]
        (log/info (format "driver/describe-table: column-info=%s" column-info))
        {:schema nil
         :name   (:name table)
         :fields (set (for [[field info] column-info]
                        (describe-table-field field info)))}))))
