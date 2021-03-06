;   This file is part of Bob.
;
;   Bob is free software: you can redistribute it and/or modify
;   it under the terms of the GNU Affero General Public License as published by
;   the Free Software Foundation, either version 3 of the License, or
;   (at your option) any later version.
;
;   Bob is distributed in the hope that it will be useful,
;   but WITHOUT ANY WARRANTY; without even the implied warranty of
;   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;   GNU Affero General Public License for more details.
;
;   You should have received a copy of the GNU Affero General Public License
;   along with Bob. If not, see <http://www.gnu.org/licenses/>.

(ns entities.system
  (:require [com.stuartsierra.component :as component]
            [environ.core :as env]
            [crux.api :as crux]
            [crux.jdbc :as jdbc]
            [taoensso.timbre :as log]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.exchange :as le]
            [langohr.consumers :as lc]
            [failjure.core :as f]
            [entities.dispatch :as d])
  (:import [java.net ConnectException]))

(defn int-from-env
  [key default]
  (try
    (Integer/parseInt (get env/env key (str default)))
    (catch Exception _ default)))

(defonce storage-host (:bob-storage-host env/env "localhost"))
(defonce storage-port (int-from-env :bob-storage-port 5432))
(defonce storage-user (:bob-storage-user env/env "bob"))
(defonce storage-name (:bob-storage-database env/env "bob"))
(defonce storage-password (:bob-storage-password env/env "bob"))

(defonce queue-host (:bob-queue-host env/env "localhost"))
(defonce queue-port (int-from-env :bob-queue-port 5672))
(defonce queue-user (:bob-queue-user env/env "guest"))
(defonce queue-password (:bob-queue-password env/env "guest"))

(defonce connection-retry-attempts (int-from-env :bob-connection-retry-attempts 10))
(defonce connection-retry-delay (int-from-env :bob-connection-retry-delay 2000))

(defn try-connect
  ([conn-fn]
   (try-connect conn-fn connection-retry-attempts))
  ([conn-fn n]
   (if (= n 0)
     (throw (ConnectException. "Cannot connect to system"))
     (let [res (f/try*
                 (conn-fn))]
       (if (f/failed? res)
         (do
           (log/warnf "Connection failed with %s, retrying %d" (f/message res) n)
           (Thread/sleep connection-retry-delay)
           (recur conn-fn (dec n)))
         res)))))

(defprotocol IDatabase
  (db-client [this]))

(defrecord Database
  [db-name db-host db-port db-user db-password]
  component/Lifecycle
  (start [this]
    (log/info "Connecting to DB")
    (assoc this
           :client
           (try-connect
             #(crux/start-node {::jdbc/connection-pool {:dialect 'crux.jdbc.psql/->dialect
                                                        :db-spec {:dbname   db-name
                                                                  :host     db-host
                                                                  :port     db-port
                                                                  :user     db-user
                                                                  :password db-password}}
                                :crux/tx-log           {:crux/module     `crux.jdbc/->tx-log
                                                        :connection-pool ::jdbc/connection-pool}
                                :crux/document-store   {:crux/module     `crux.jdbc/->document-store
                                                        :connection-pool ::jdbc/connection-pool}}))))
  (stop [this]
    (log/info "Disconnecting DB")
    (.close (:client this))
    (assoc this :client nil))
  IDatabase
  (db-client [this]
             (:client this)))

(defprotocol IQueue
  (queue-chan [this]))

(defrecord Queue
  [database queue-host queue-port queue-user queue-password]
  component/Lifecycle
  (start [this]
    (let [conn            (try-connect #(rmq/connect {:host     queue-host
                                                      :port     queue-port
                                                      :username queue-user
                                                      :vhost    "/"
                                                      :password queue-password}))
          chan            (lch/open conn)
          entities-queue  "bob.entities"
          direct-exchange "bob.direct"
          error-queue     "bob.errors"]
      (log/infof "Connected on channel id: %d" (.getChannelNumber chan))
      (le/declare chan direct-exchange "direct" {:durable true})
      (lq/declare chan
                  entities-queue
                  {:exclusive   false
                   :auto-delete false
                   :durable     true})
      (lq/declare chan
                  error-queue
                  {:exclusive   false
                   :auto-delete false
                   :durable     true})
      (lq/bind chan entities-queue direct-exchange {:routing-key entities-queue})
      (lc/subscribe chan entities-queue (partial d/queue-msg-subscriber (db-client database)) {:auto-ack true})
      (log/infof "Subscribed to %s" entities-queue)
      (assoc this :conn conn :chan chan)))
  (stop [this]
    (log/info "Disconnecting queue")
    (rmq/close (:conn this))
    (assoc this :conn nil :chan nil))
  IQueue
  (queue-chan [this] (:chan this)))

(def system-map
  (component/system-map
    :queue    (component/using (map->Queue {:queue-host     queue-host
                                            :queue-port     queue-port
                                            :queue-user     queue-user
                                            :queue-password queue-password})
                               [:database])
    :database (map->Database {:db-name     storage-name
                              :db-host     storage-host
                              :db-port     storage-port
                              :db-user     storage-user
                              :db-password storage-password})))

(defonce system nil)

(defn start
  []
  (alter-var-root #'system
                  (constantly (component/start system-map))))

(defn stop
  []
  (alter-var-root #'system
                  #(when %
                     (component/stop %))))

(defn reset
  []
  (stop)
  (start))

(comment
  (reset)

  (int-from-env :yalla 42))
