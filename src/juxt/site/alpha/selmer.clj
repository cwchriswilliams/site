;; Copyright © 2021, JUXT LTD.

(ns juxt.site.alpha.selmer
  (:require
   [clojure.tools.logging :as log]
   [clojure.walk :refer [postwalk]]
   [crux.api :as x]
   [juxt.site.alpha.templating :as templating]
   [juxt.site.alpha.util :as util]
   [selmer.parser :as selmer])
  (:import (java.net URL)))

(alias 'site (create-ns 'juxt.site.alpha))
(alias 'http (create-ns 'juxt.http.alpha))
(alias 'pass (create-ns 'juxt.pass.alpha))

(selmer/cache-off!)

(defmethod templating/render-template
  :selmer
  [{::site/keys [db resource selected-representation] :as req} template]
  (let [{::site/keys [] id :crux.db/id} selected-representation
        _ (assert id "Resource must have an id to be used as a template")
        ush (proxy [java.net.URLStreamHandler] []
              (openConnection [url]
                (log/tracef "Open connection: url=%s" url)
                (proxy [java.net.URLConnection] [url]
                  (getInputStream []
                    (log/tracef "Loading template: url=%s" url)
                    (let [res (x/entity db (str url))]
                      (java.io.ByteArrayInputStream.
                       (cond
                         (::http/content res) (.getBytes (::http/content res) (or (::http/charset res) "UTF-8"))
                         (::http/body res) (::http/body res)
                         :else (.getBytes "(template not found)"))))))))

        temp-id-map
        (->>
         {'subject (::pass/subject req)
          'resource resource
          'request (select-keys
                    req
                    [:ring.request/headers :ring.request/method :ring.request/path
                     :ring.request/query :ring.request/protocol :ring.request/remote-addr
                     :ring.request/scheme :ring.request/server-name :ring.request/server-post
                     :ring.request/ssl-client-cert])}
         (reduce-kv
          ;; Preserve any existing crux.db/id - e.g. the resource will have one
          (fn [acc k v]
            (assoc acc k (-> v
                             util/->freezeable
                             (assoc :crux.db/id (java.util.UUID/randomUUID)))))
          {}))

        txes (vec (for [[_ v] temp-id-map] [:crux.tx/put v]))

        spec-db (x/with-tx db txes)

        template-model (::site/template-model resource)

        template-model
        (postwalk
         (fn [m]
           (cond
             (and (map? m) (contains? m ::site/query))
             (cond-> (apply x/q spec-db
                            (assoc (::site/query m) :in (vec (keys temp-id-map)))
                            (map :crux.db/id (vals temp-id-map)))
               (= (::site/results m) 'first) first)
             :else m))
         template-model)

        custom-resource-path (:selmer.util/custom-resource-path template)]

    (try
      (log/tracef "Render template: %s" (:crux.db/id template))
      (selmer/render-file
       (java.net.URL. nil (:crux.db/id template) ush)
       template-model
       (cond-> {:url-stream-handler ush}
         custom-resource-path
         (assoc :custom-resource-path custom-resource-path)))

      (catch Exception e
        (throw (ex-info (str "Failed to render template: " template) {:template template
                                                                      :exception-type (type e)} e))))))