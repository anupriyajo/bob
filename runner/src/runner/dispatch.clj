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

(ns runner.dispatch
  (:require [taoensso.timbre :as log]
            [jsonista.core :as json]
            [failjure.core :as f]
            [runner.pipeline :as p]
            [runner.errors :as err]))

(def ^:private routes
  {"pipeline/start" p/start
   "pipeline/stop"  p/stop})

(def mapper (json/object-mapper {:decode-key-fn true}))

(defn queue-msg-subscriber
  [db-conn chan meta-data payload]
  (let [msg (f/try*
              (json/read-value payload mapper))]
    (if (f/failed? msg)
      (err/publish-error chan (format "Could not parse '%s' as json" (String. payload "UTF-8")))
      (do
        (log/infof "payload %s, meta: %s"
                   msg
                   meta-data)
        (if-let [routed-fn (some-> meta-data
                                   :type
                                   routes)]
          (routed-fn db-conn chan msg)
          (err/publish-error chan (format "Could not route message: %s" msg)))))))
