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

(ns entities.dispatch
  (:require [taoensso.timbre :as log]
            [jsonista.core :as json]
            [failjure.core :as f]
            [entities.pipeline.core :as pipeline]
            [entities.artifact-store.core :as artifact-store]
            [entities.resource-provider.core :as resource-provider]))

(def ^:private routes
  {"pipeline/create"          pipeline/create
   "pipeline/delete"          pipeline/delete
   "artifact-store/create"    artifact-store/register-artifact-store
   "artifact-store/delete"    artifact-store/un-register-artifact-store
   "resource-provider/create" resource-provider/register-resource-provider
   "resource-provider/delete" resource-provider/un-register-resource-provider})

(defn route
  [db-conn message]
  (log/debugf "Routing message: %s" message)
  (if-let [routed-fn (some-> message
                             :type
                             routes)]
    (routed-fn db-conn (:payload message))
    (log/errorf "Could not route message: %s" message)))

(def mapper (json/object-mapper {:decode-key-fn true}))

(defn queue-msg-subscriber
  [db-conn _chan meta-data payload]
  (let [msg (f/try* (json/read-value payload mapper))]
    (if (f/failed? msg)
      (log/errorf "Could not parse %s as json" payload)
      (do
        (log/infof "payload %s" msg)
        (log/infof "meta %s" meta-data)
        (route db-conn
               {:type    (-> meta-data
                             :type
                             keyword)
                :payload msg})))))