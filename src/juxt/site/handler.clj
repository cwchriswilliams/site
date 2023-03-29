;; Copyright © 2021, JUXT LTD.

(ns juxt.site.handler
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [jsonista.core :as json]
   [juxt.pick.core :refer [rate-representation]]
   [juxt.pick.ring :refer [decode-maybe]]
   [juxt.reap.alpha.decoders.rfc7230 :as rfc7230.decoders]
   [juxt.reap.alpha.encoders :refer [format-http-date]]
   [juxt.reap.alpha.regex :as re]
   [juxt.reap.alpha.rfc7230 :as-alias rfc7230]
   [juxt.reap.alpha.ring :refer [headers->decoded-preferences]]
   [juxt.site.operations :as operations]
   [juxt.site.cache :as cache]
   [juxt.site.conditional :as conditional]
   [juxt.site.content-negotiation :as conneg]
   [juxt.site.http-authentication :as http-authn]
   [juxt.site.locator :as locator]
   [juxt.site.response :as response]
   [juxt.site.session-scope :as session-scope]
   [juxt.site.util :as util]
   [ring.util.codec :as codec]
   [selmer.parser :as selmer]
   [sci.core :as sci]
   [xtdb.api :as xt])
  (:import (java.net URI)))

(defn join-keywords
  "Join method keywords into a single comma-separated string. Used for the Allow
  response header value, and others."
  [methods upper-case?]
  (->>
   methods
   seq
   distinct
   (map (comp (if upper-case? str/upper-case identity) name))
   (str/join ", ")))

(defn receive-representation
  "Check and load the representation enclosed in the request message payload."
  [{:juxt.site/keys [resource start-date] :ring.request/keys [method] :as req}]

  (log/debugf "Receiving representation, for resource %s" (:xt/id resource))

  (let [content-length
        (try
          (some->
           (get-in req [:ring.request/headers "content-length"])
           (Long/parseLong))
          (catch NumberFormatException e
            (throw
             (ex-info
              "Bad content length"
              {:ring.response/status 400
               :juxt.site/request-context req}
              e))))]

    (when (nil? content-length)
      (throw
       (ex-info
        "No Content-Length header found"
        {:ring.response/status 411
         :juxt.site/request-context req})))

    ;; Protects resources from PUTs that are too large. If you need to
    ;; exceed this limitation, explicitly declare ::spin/max-content-length in
    ;; your resource.
    (when-let [max-content-length (get resource :juxt.http/max-content-length (Math/pow 2 24))] ;;16MB
      (when (> content-length max-content-length)
        (throw
         (ex-info
          "Payload too large"
          {:ring.response/status 413
           :juxt.site/request-context req}))))

    (when-not (:ring.request/body req)
      (throw
       (ex-info
        "No body in request"
        {:ring.response/status 400
         :juxt.site/request-context req})))

    (let [decoded-representation
          (decode-maybe

           ;; See Section 3.1.1.5, RFC 7231 as to why content-type defaults
           ;; to application/octet-stream
           (cond-> {:juxt.http/content-type "application/octet-stream"}
             (contains? (:ring.request/headers req) "content-type")
             (assoc :juxt.http/content-type (get-in req [:ring.request/headers "content-type"]))

             (contains? (:ring.request/headers req) "content-encoding")
             (assoc :juxt.http/content-encoding (get-in req [:ring.request/headers "content-encoding"]))

             (contains? (:ring.request/headers req) "content-language")
             (assoc :juxt.http/content-language (get-in req [:ring.request/headers "content-language"]))))]

      ;; TODO: Someday there should be a functions that could be specified to
      ;; handle conversions as described in RFC 7231 Section 4.3.4

      ;; TODO: Add tests for content type (and other axes of) acceptance of PUT
      ;; and POST

      (when-let [acceptable
                 (get-in resource [:juxt.site/methods method :juxt.site/acceptable])]

        (let [prefs (headers->decoded-preferences acceptable)
              request-rep (rate-representation prefs decoded-representation)]

          (when (or (get prefs "accept") (get prefs "accept-charset"))
            (cond
              (= (:juxt.pick/content-type-qvalue request-rep) 0.0)
              (throw
               (ex-info
                "The content-type of the request payload is not supported by the resource"
                {:ring.response/status 415
                 ::acceptable acceptable
                 ::content-type (get request-rep "content-type")
                 :juxt.site/request-context req}))

              (and
               (= "text" (get-in request-rep [:juxt.reap.alpha.rfc7231/content-type :juxt.reap.alpha.rfc7231/type]))
               (get prefs "accept-charset")
               (not (contains? (get-in request-rep [:juxt.reap.alpha.rfc7231/content-type :juxt.reap.alpha.rfc7231/parameter-map]) "charset")))
              (throw
               (ex-info
                "The Content-Type header in the request is a text type and is required to specify its charset as a media-type parameter"
                {:ring.response/status 415
                 ::acceptable acceptable
                 ::content-type (get request-rep "content-type")
                 :juxt.site/request-context req}))

              (= (:juxt.pick/charset-qvalue request-rep) 0.0)
              (throw
               (ex-info
                "The charset of the Content-Type header in the request is not supported by the resource"
                {:ring.response/status 415
                 ::acceptable acceptable
                 ::content-type (get request-rep "content-type")
                 :juxt.site/request-context req}))))

          (when (get prefs "accept-encoding")
            (cond
              (= (:juxt.pick/content-encoding-qvalue request-rep) 0.0)
              (throw
               (ex-info
                "The content-encoding in the request is not supported by the resource"
                {:ring.response/status 409
                 ::acceptable acceptable
                 ::content-encoding (get-in req [:ring.request/headers "content-encoding"] "identity")
                 :juxt.site/request-context req}))))

          (when (get prefs "accept-language")
            (cond
              (not (contains? (:ring.response/headers req) "content-language"))
              (throw
               (ex-info
                "Request must contain Content-Language header"
                {:ring.response/status 409
                 ::acceptable acceptable
                 ::content-language (get-in req [:ring.request/headers "content-language"])
                 :juxt.site/request-context req}))

              (= (:juxt.pick/content-language-qvalue request-rep) 0.0)
              (throw
               (ex-info
                "The content-language in the request is not supported by the resource"
                {:ring.response/status 415
                 ::acceptable acceptable
                 ::content-language (get-in req [:ring.request/headers "content-language"])
                 :juxt.site/request-context req}))))))

      (when (get-in req [:ring.request/headers "content-range"])
        (throw
         (ex-info
          "Content-Range header not allowed on a PUT request"
          {:ring.response/status 400
           :juxt.site/request-context req})))

      (with-open [in (:ring.request/body req)]
        (let [body (.readNBytes in content-length)
              content-type (:juxt.reap.alpha.rfc7231/content-type decoded-representation)]

          (assoc
           req
           :juxt.site/received-representation
           (merge
            decoded-representation
            {:juxt.http/content-length content-length
             :juxt.http/last-modified start-date}

            (if (and
                 (= (:juxt.reap.alpha.rfc7231/type content-type) "text")
                 (nil? (get decoded-representation :juxt.http/content-encoding)))
              (let [charset
                    (get-in decoded-representation
                            [:juxt.reap.alpha.rfc7231/content-type :juxt.reap.alpha.rfc7231/parameter-map "charset"])]
                (merge
                 {:juxt.http/content (new String body (or charset "utf-8"))}
                 (when charset {:juxt.http/charset charset})))

              {:juxt.http/body body}))))))))

(defn GET [{:juxt.site/keys [resource subject]
            :as req}]

  ;; TODO: Is a HEAD cacheable?

  (conditional/evaluate-preconditions! req)

  (let [req (assoc req :ring.response/status 200)
        operation (:juxt.site/operation req)]

    (cond

      ;; It's rare but sometimes a GET will involve a transaction. For example,
      ;; the Authorization Request (RFC 6749 Section 4.2.1).
      (and operation (:juxt.site/transact operation))
      (operations/do-operation!
       (cond-> req
         ;; A java.io.BufferedInputStream in the request can cause this error:
         ;; "invalid tx-op: Unfreezable type: class
         ;; java.io.BufferedInputStream".
         (:ring.request/body req) (dissoc :ring.request/body)))

      (-> resource :juxt.site/respond :juxt.site.sci/program)
      (let [state
            (when-let [program (-> operation :juxt.site/state :juxt.site.sci/program)]
              (sci/binding [sci/out *out*]
                (sci/eval-string
                 program
                 {:namespaces
                  (merge
                   {'user {'*operation* operation
                           '*resource* (:juxt.site/resource req)
                           '*ctx* (dissoc req :juxt.site/xt-node)
                           'log (fn [& message]
                                  (eval `(log/info ~(str/join " " message))))
                           'logf (fn [& args]
                                   (eval `(log/infof ~@args)))}
                    'xt
                    {'entity
                     (fn [id] (xt/entity (:juxt.site/db req) id))
                     'pull
                     (fn [query eid]
                       (xt/pull (:juxt.site/db req) query eid))
                     'q
                     (fn [query & args]
                       (apply xt/q (:juxt.site/db req) query args))
                     }

                    'juxt.site
                    {'pull-allowed-resources
                     (fn [m]
                       (operations/pull-allowed-resources
                        (:juxt.site/db req)
                        m
                        {:juxt.site/subject subject
                         ;; TODO: Don't forget purpose
                         }))
                     'allowed-operations
                     (fn [m]
                       (operations/allowed-operations
                        (:juxt.site/db req)
                        (merge {:juxt.site/subject subject} m)))}})

                  :classes
                  {'java.util.Date java.util.Date
                   'java.time.Instant java.time.Instant
                   'java.time.Duration java.time.Duration}})))

            respond-program
            (-> resource :juxt.site/respond :juxt.site.sci/program)

            response
            (sci/eval-string
             respond-program
             {:namespaces
              (merge
               {'user (cond->
                          {'*operation* operation
                           '*resource* (:juxt.site/resource req)
                           '*ctx* (dissoc req :juxt.site/xt-node)
                           'logf (fn [& args] (eval `(log/debugf ~@args)))
                           'log (fn [& args] (eval `(log/debug ~@args)))}
                        state (assoc '*state* state))

                'jsonista.core
                {'write-value-as-string json/write-value-as-string
                 'read-value json/read-value}

                'ring.util.codec
                {'form-encode codec/form-encode
                 'form-decode codec/form-decode
                 'url-encode codec/url-encode
                 'url-decode codec/url-decode}

                'selmer
                {'render (fn [s context-map] (selmer/render s context-map))}

                'xt
                { ;; Unsafe due to violation of strict serializability, hence marked as
                 ;; entity*
                 'entity*
                 (fn [id] (xt/entity (:juxt.site/db req) id))
                 'q (fn [& args] (apply xt/q (:juxt.site/db req) args))}})})

            _ (assert
               (:juxt.site/start-date response)
               "Representation response script must return a request context")]

        ;; TODO: Don't add the response if a HEAD.  A HEAD does still
        ;; need to call the response program, because it may set
        ;; various other response headers.

        (log/tracef "Response is: %s" (pr-str (select-keys response [:ring.response/status :ring.response/headers :ring.response/body])))
        response)

      :else
      (cond-> req
        (= (:ring.request/method req) :get)
        response/add-payload))))

(defn perform-unsafe-method [{:keys [ring.request/method] :as req}]
  (let [req (cond-> req
              (#{:post :put :patch} method) receive-representation)

        permissions (:juxt.site/permissions req)
        _ (assert permissions)

        ;; Default response status
        req (assoc req :ring.response/status 200)]

    (operations/do-operation!
     (-> req
         ;; A java.io.BufferedInputStream in the request can provoke this
         ;; error: "invalid tx-op: Unfreezable type: class
         ;; java.io.BufferedInputStream".
         (dissoc :ring.request/body)))))

(defn POST [req]
  (perform-unsafe-method req))

(defn PUT [req]
  (perform-unsafe-method req))

(defn PATCH [req]
  (perform-unsafe-method req))

(defn DELETE [req]
  (perform-unsafe-method req))

(defn OPTIONS [{:juxt.site/keys [allowed-methods] :as req}]
  (-> req
      (assoc :ring.response/status 200)
      (update :ring.response/headers
              merge
              ;;                   (:juxt.http/options resource)
              {"allow" (join-keywords allowed-methods true)
               ;; TODO: Shouldn't this be a situation (a missing body) detected
               ;; by middleware, which can set the content-length header
               ;; accordingly?
               "content-length" "0"})))

(defn wrap-method-not-implemented? [h]
  (fn [{:ring.request/keys [method] :as req}]
    (when-not (contains?
               #{:get :head :post :put :delete :options
                 :patch
                 :mkcol :propfind} method)
      (throw
       (ex-info
        "Method not implemented"
        {:ring.response/status 501
         :juxt.site/request-context req})))
    (h req)))

(defn wrap-locate-resource [h]
  (fn [req]
    (let [res (locator/locate-resource req)]
      (log/debugf "Resource provider: %s" (:juxt.site/resource-provider res))
      (h (assoc req :juxt.site/resource res)))))

(defn wrap-find-current-representations
  [h]
  (fn [{:ring.request/keys [method]
        :as req}]
    (if (#{:get :head :put} method)
      (let [cur-reps (seq (conneg/current-representations req))]
        (when (and
               (#{:get :head} method)
               (empty? cur-reps)
                ;; We might have an operation installed for the GET method. This is
                ;; rare but used for certain cases such as special
                ;; redirections. In this case, we don't throw a 404, but return
               ;; the result of the operation.
               ;; New note: let's not do this, but rather ensure that there is content, even if it's null content, on the redirection resource.
               ;;(empty? (get-in resource [:juxt.site/methods :get :juxt.site/operations]))
               )
          (throw
           (ex-info
            "Not Found"
            {:ring.response/status 404
             :juxt.site/request-context req})))
        (h (assoc req :juxt.site/current-representations cur-reps)))
      (h req))))

(defn wrap-negotiate-representation [h]
  (fn [{base-resource :juxt.site/resource
        cur-reps :juxt.site/current-representations
        :as req}]
    (let [variant (when (seq cur-reps)
                    (conneg/negotiate-representation req cur-reps))
          variant
          (when variant
            (cond-> variant
              (not= (:xt/id variant) (:xt/id base-resource))
              (assoc :juxt.site/variant-of (:xt/id base-resource))))]
      (h (cond-> req
           variant (assoc :juxt.site/resource variant))))))

(defn wrap-http-authenticate [h]
  (fn [req]
    (h (http-authn/authenticate req))))

(defn wrap-method-not-allowed? [h]
  (fn [{:juxt.site/keys [resource] :ring.request/keys [method] :as req}]

    (if resource
      (let [allowed-methods (set (keys (:juxt.site/methods resource)))
            ;; allowed-methods will be an empty set if
            ;; no :juxt.site/methods on the resource.
            allowed-methods (cond-> allowed-methods
                              (contains? allowed-methods :get)
                              (conj :head)
                              true (conj :options))]
        (when-not (contains? allowed-methods method)
          (throw
           (ex-info
            "Method not allowed"
            {:ring.response/status 405
             :ring.response/headers {"allow" (join-keywords allowed-methods true)}
             :method method
             :juxt.site/allowed-methods allowed-methods
             :juxt.site/request-context req})))
        (h (assoc req :juxt.site/allowed-methods allowed-methods)))
      (h req))))

(defn wrap-invoke-method [h]
  (fn [{:ring.request/keys [method] :as req}]

    ;; Temporary assert while tracking down an issue
    (assert (or (nil? (find req :juxt.site/subject)) (map? (:juxt.site/subject req)))
            (format "Subject must be a map, or nil: %s" (pr-str (find req :juxt.site/subject))))

    (h (case method
         (:get :head) (GET req)
         :post (POST req)
         :put (PUT req)
         :patch (PATCH req)
         :delete (DELETE req)
         :options (OPTIONS req)))))

(defn wrap-security-headers [h]
  (fn [req]
    ;; TODO - but see wrap-cors-headers middleware
    ;;        Strict-Transport-Security: max-age=31536000; includeSubdomains
    ;;        X-Frame-Options: DENY
    ;;        X-Content-Type-Options: nosniff
    ;;        X-XSS-Protection: 1; mode=block
    ;;        X-Download-Options: noopen
    ;;        X-Permitted-Cross-Domain-Policies: none

    (let [res (h req)]
      (cond-> res
        ;; Don't allow Google to track your site visitors. Disable FLoC.
        true (assoc-in [:ring.response/headers "Permissions-Policy"] "interest-cohort=()")))))

(defn titlecase-response-headers [headers]
  (reduce-kv
   (fn [acc k v]
     (assoc acc (str/replace k #"(?<=^|-)[a-z]" str/upper-case) v))
   {}
   headers))

(defn lowercase-response-headers [headers]
  (reduce-kv
   (fn [acc k v]
     (assoc acc (str/lower-case k) v))
   {}
   headers))

(defn add-headers [headers
                   {:ring.request/keys [method]
                    :juxt.site/keys [resource]}
                   body]
  (let [method (if (= method :head) :get method)]
    (cond-> headers
      (:juxt.http/content-length resource)
      (assoc "content-length" (or
                               (some-> resource :juxt.http/content-length str)
                               (when (counted? body) (some-> body count str))))

      ;; Some of these are marked as deprecated.  Entries in
      ;; ring.response/headers must be fully processed strings and
      ;; cannot support collections to be automatically comma separated
      ;; by Site. (e.g. access-control-allow-methods). So we'll keep
      ;; some of them, but anything that can be included in
      ;; ring.response/headers should be deprecated/removed.

      ;; Deprecated
      (:juxt.http/content-type resource)
      (assoc "content-type" (:juxt.http/content-type resource))

      ;; Deprecated
      (:juxt.http/content-encoding resource)
      (assoc "content-encoding" (:juxt.http/content-encoding resource))

      ;; Deprecated
      (:juxt.http/content-language resource)
      (assoc "content-language" (:juxt.http/content-language resource))

      ;; Deprecated
      (:juxt.http/content-location resource)
      (assoc "content-location" (:juxt.http/content-location resource))

      ;; Deprecated
      (:juxt.http/last-modified resource)
      (assoc "last-modified" (:juxt.http/last-modified resource))

      ;; Deprecated
      (:juxt.http/etag resource)
      (assoc "etag" (:juxt.http/etag resource))

      ;; Deprecated
      (:juxt.http/vary resource)
      (assoc "vary" (:juxt.http/vary resource))

      ;; Deprecated
      (:juxt.http/content-range resource)
      (assoc "content-range" (:juxt.http/content-range resource))

      ;; Deprecated
      (:juxt.http/trailer resource)
      (assoc "trailer" (:juxt.http/trailer resource))

      ;; Deprecated
      (:juxt.http/transfer-encoding resource)
      (assoc "transfer-encoding" (:juxt.http/transfer-encoding resource))

      ;; Keep
      (get-in resource [:juxt.site/methods method :ring.response/headers])
      (merge (lowercase-response-headers (get-in resource [:juxt.site/methods method :ring.response/headers]))))))

(defn redact [req]
  (-> req
      (update :ring.request/headers
              (fn [headers]
                (cond-> headers
                  ;; TODO: Add cookies to this
                  (contains? headers "authorization")
                  (assoc "authorization" "(redacted)"))))))

(defn ->storable [{:juxt.site/keys [request-id db] :as req}]
  (-> req
      (into (select-keys db [:xtdb.api/valid-time :xtdb.api/tx-id]))
      (assoc :xt/id request-id :juxt.site/type "Request")
      redact
      (dissoc :juxt.site/xt-node :juxt.site/db :ring.request/body :ring.response/body)
      (util/deep-replace
       (fn [form]
         (cond-> form
           (and (string? form) (>= (count form) 1024))
           (subs 0 1024)

           (and (vector? form) (>= (count form) 64))
           (subvec 0 64)

           (and (list? form) (>= (count form) 64))
           (#(take 64 %)))))))

(defn log-request! [{:ring.request/keys [method]
                     :juxt.site/keys [uri]
                     :as req}]
  (assert method)
  (log/infof
   "%-7s %s %d"
   (str/upper-case (name method))
   uri
   (:ring.response/status req)))

(defn respond
  [{:juxt.site/keys [resource start-date request-id]
    :ring.request/keys [method]
    :ring.response/keys [body]
    :as req}]

  (assert req)
  (assert start-date "this is a guard to make sure we aren't receiving back a corrupt request context")

  (let [end-date (java.util.Date.)
        req (assoc req
                   :juxt.site/end-date end-date
                   :juxt.site/date end-date
                   :juxt.site/duration-millis (- (.getTime end-date)
                                             (.getTime start-date)))]
    (cond->
        (update req
                :ring.response/headers
                assoc "date" (format-http-date start-date))

        request-id
        (update :ring.response/headers assoc "Site-Request-Id" request-id)

        (and resource (#{:get :head} method))
        (update :ring.response/headers add-headers req body)

        (= method :head) (dissoc :ring.response/body))))

(defn wrap-initialize-response [h]
  (fn [req]
    (assert req)
    (let [response (h req)]
      (assert response)
      (respond response))))

(defn access-control-match-origin [allow-origins origin]
  (some (fn [[pattern result]]
          (when (re-matches (re-pattern pattern) origin)
            result))
        allow-origins))

(defn wrap-cors-headers [h]
  (fn [req]
    (let [{:juxt.site/keys [resource] :as req} (h req)

          ;; The access-control declarations will be on the main
          ;; resource, not the variant.  WARNING: If restoring this,
          ;; make sure we look up the resource as a map from the id!!
          ;; resource (or (:juxt.site/variant-of resource) resource)


          request-origin (get-in req [:ring.request/headers "origin"])
          {:juxt.site/keys [access-control-allow-origins]} resource

          access-control
          (when request-origin
            (access-control-match-origin access-control-allow-origins request-origin))]

      (cond-> req
        access-control
        (assoc-in [:ring.response/headers "access-control-allow-origin"] request-origin)

        (contains? access-control :juxt.site/access-control-allow-methods)
        (assoc-in [:ring.response/headers "access-control-allow-methods"]
                  (join-keywords (get access-control :juxt.site/access-control-allow-methods) true))
        (contains? access-control :juxt.site/access-control-allow-headers)
        (assoc-in [:ring.response/headers "access-control-allow-headers"]
                  (join-keywords (get access-control :juxt.site/access-control-allow-headers) false))
        (contains? access-control :juxt.site/access-control-allow-credentials)
        (assoc-in [:ring.response/headers "access-control-allow-credentials"]
                  (str (get access-control :juxt.site/access-control-allow-credentials)))))))

(defn status-message [status]
  (case status
    200 "OK"
    201 "Created"
    202 "Accepted"
    204 "No Content"
    302 "Found"
    307 "Temporary Redirect"
    304 "Not Modified"
    400 "Bad Request"
    401 "Unauthorized"
    403 "Forbidden"
    404 "Not Found"
    405 "Method Not Allowed"
    409 "Conflict"
    411 "Length Required"
    412 "Precondition Failed"
    413 "Payload Too Large"
    415 "Unsupported Media Type"
    500 "Internal Server Error"
    501 "Not Implemented"
    503 "Service Unavailable"
    "Error"))

(defn errors-with-causes
  "Return a collection of errors with their messages and causes"
  [e]
  (let [cause (.getCause e)]
    (cons
     (cond->
         {:message (.getMessage e)
          :stack-trace (.getStackTrace e)}
       (instance? clojure.lang.ExceptionInfo e)
       (assoc :ex-data (dissoc (ex-data e) :juxt.site/request-context)))
     (when cause (errors-with-causes cause)))))

(defn put-error-representation
  "If method is PUT"
  [{:juxt.site/keys [resource] :ring.response/keys [status] :as req}]
  (let [{:juxt.http/keys [put-error-representations]} resource
        put-error-representations
        (filter
         (fn [rep]
           (if-some [applies-to (:ring.response/status rep)]
             (= applies-to status)
             true)) put-error-representations)]

    (when (seq put-error-representations)
      (some-> (conneg/negotiate-representation req put-error-representations)
              ;; Content-Location is not appropriate for errors.
              (dissoc :juxt.http/content-location)))))

(defn post-error-representation
  "If method is POST"
  [{:juxt.site/keys [resource] :ring.response/keys [status] :as req}]
  (let [{:juxt.http/keys [post-error-representations]} resource
        post-error-representations
        (filter
         (fn [rep]
           (if-some [applies-to (:ring.response/status rep)]
             (= applies-to status)
             true)) post-error-representations)]

    (when (seq post-error-representations)
      (some-> (conneg/negotiate-representation req post-error-representations)
              ;; Content-Location is not appropriate for errors.
              (dissoc :juxt.http/content-location)))))

;; TODO: I'm beginning to think that a resource should include the handling of
;; all possible errors and error representations. So there shouldn't be such a
;; thing as an 'error resource'. In this perspective, site-wide 'common'
;; policies, such as a common 404 page, can be merged into the resource by the
;; resource locator. Whether a user-agent is given error information could be
;; subject to policy which is part of a common configuration. But the resource
;; itself should be ignorant of such policies. Additionally, this is more
;; aligned to OpenAPI's declaration of per-resource errors.

(defn error-resource
  "Locate an error resource. Currently only uses a simple database lookup of an
  'ErrorResource' entity matching the status. In future this could use rules to
  use the environment (dev vs. prod), subject (developer vs. customer) or other
  variables to determine the resource to use."
  [{:juxt.site/keys [db]} status]
  (when-let [res (ffirst
            (xt/q db '{:find [(pull er [*])]
                      :where [[er :juxt.site/type "ErrorResource"]
                              [er :ring.response/status status]]
                      :in [status]} status))]
    (log/debugf "ErrorResource found for status %d: %s" status res)
    res))

(defn error-resource-representation
  "Experimental. Not sure this is a good idea to have a 'global' error
  resource. Better to merge error handling into each resource (using the
  resource locator)."
  [req]
  (let [{:ring.response/keys [status]} req]

    (when-let [er (error-resource req (or status 500))]
      (let [
            ;; Allow errors to be transmitted to developers
            er
            (assoc er
                   :juxt.site/access-control-allow-origins
                   [[".*" {:juxt.site/access-control-allow-origin "*"
                           :juxt.site/access-control-allow-methods [:get :put :post :delete]
                           :juxt.site/access-control-allow-headers ["authorization" "content-type"]}]])

            er
            (-> er
                #_(update :juxt.site/template-model assoc
                          "_site" {"status" {"code" status
                                             "message" (status-message status)}
                                   "error" {"message" (.getMessage e)}
                                   "uri" (:juxt.site/uri req)}))

            error-representations
            (conneg/current-representations
             (assoc req
                    :juxt.site/resource er
                    :juxt.site/uri (:xt/id er)))]

        (when (seq error-representations)
          (some-> (conneg/negotiate-representation req error-representations)
                  ;; Content-Location is not appropriate for errors.
                  (dissoc :juxt.http/content-location)))))))

(defn respond-internal-error [{:juxt.site/keys [request-id] :as req} e]
  (log/error e (str "Internal Error: " (.getMessage e)))
  ;; TODO: We should allow an ErrorResource for 500 errors

  ;; TODO: Negotiate a better format for internal server errors

  (let [default-body
        (str "<body>\r\n"
             (cond-> "<h1>Internal Server Error</h1>\r\n"
               request-id (str (format "<p><a href=\"%s\" target=\"_site_error\">%s</a></p>\r\n" request-id "Error")))
             "</body>\r\n")]
    (respond
     (into
      req
      {:ring.response/status 500
       :ring.response/body default-body
       :juxt.site/errors (errors-with-causes e)
       :juxt.site/resource
       {:juxt.http/content-type "text/html;charset=utf-8"
        :juxt.http/content-length (count default-body)
        :ring.response/body default-body}
       }))))

(defn error-response
  "Respond with the given error"
  [req e]

  (assert (:juxt.site/start-date req))

  (let [{:ring.response/keys [status]
         :ring.request/keys [method]
         :juxt.site/keys [request-id]} req

        representation
        (or
         (when (= method :put) (put-error-representation req))
         (when (= method :post) (post-error-representation req))
         (error-resource-representation req)

         ;; Some default representations for errors
         (some->
          (conneg/negotiate-error-representation
           req
           [(let [content
                  ;; We don't want to provide much information here, we don't
                  ;; know much about the recipient, only that they're probably
                  ;; using a web browser. We provide a link to the error
                  ;; resource, because that will be subject to authorization
                  ;; checks. So authorized users get to see extensive error
                  ;; information, unauthorized users don't.
                  (cond-> (str (status-message status) "\r\n")
                    request-id (str (format "<a href=\"%s\" target=\"_site_error\">%s</a>\r\n" request-id "Error")))]
              {:juxt.http/content-type "text/html;charset=utf-8"
               :juxt.http/content-length (count content)
               :juxt.http/content content})
            (let [content (str (status-message status) "\r\n")]
              {:juxt.http/content-type "text/plain;charset=utf-8"
               :juxt.http/content-length (count content)
               :juxt.http/content content
               })])

          ;; This is an error, it won't be cached, it isn't negotiable 'content'
          ;; so the Vary header isn't deemed applicable. Let's not set it.
          (dissoc :juxt.http/vary))

         ;; A last ditch error in plain-text, even though plain-text is not acceptable, we override this
         (let [content (.getBytes (str (status-message status) "\r\n") "US-ASCII")]
           {:juxt.http/content-type "text/plain;charset=us-ascii"
            :juxt.http/content-length (count content)
            :juxt.http/content content
            :juxt.site/access-control-allow-origins
            [[".*" {:juxt.site/access-control-allow-origin "*"
                    :juxt.site/access-control-allow-methods [:get :put :post :delete]
                    :juxt.site/access-control-allow-headers ["authorization" "content-type"]}]]}))

        original-resource (:juxt.site/resource req)

        error-resource (merge
                        {:ring.response/status 500
                         :juxt.site/errors (errors-with-causes e)}
                        (dissoc req :juxt.site/request-context)

                        ;; Add CORS
                        #_{:juxt.site/access-control-allow-origins
                           [[".*" {:juxt.site/access-control-allow-origin "*"
                                   :juxt.site/access-control-allow-methods [:get :put :post :delete]
                                   :juxt.site/access-control-allow-headers ["authorization" "content-type"]}]]}

                        ;; For the error itself
                        (cond->
                            {:juxt.site/resource representation}
                            original-resource (assoc :juxt.site/original-resource original-resource)))

        error-resource (assoc
                        error-resource
                        :juxt.site/status-message (status-message (:ring.response/status error-resource)))

        response (try
                   (cond-> error-resource
                     (not= method :head) response/add-error-payload)
                   (catch Exception e
                     (respond-internal-error req e)))]

    (respond response)))


(defn wrap-error-handling
  "Return a handler that constructs proper Ring responses, logs and error
  handling where appropriate."
  [h]
  (fn [{:juxt.site/keys [request-id] :as req}]
    (org.slf4j.MDC/put "reqid" request-id)
    (try
      (h req)
      (catch clojure.lang.ExceptionInfo e
        ;; When throwing ex-info, try to add the :juxt.site/request-context key,
        ;; using the request of the cause as the base. In this way, the cause
        ;; request (which is more recent), predominates but a catcher can always
        ;; override aspects, such as the ring.response/status.

        (log/errorf e "wrap-error-handling, ex-data: %s" (pr-str (ex-data e)))

        (let [ex-data (ex-data e)
              ctx (or (:juxt.site/request-context ex-data) req)
              status (or
                      (:ring.response/status ex-data) ; explicit error status
                      500)
              headers (merge
                       (:ring.response/headers ctx)
                       (:ring.response/headers ex-data))
              ctx (cond-> ctx
                    status (assoc :ring.response/status status)
                    headers (assoc :ring.response/headers headers))]

          (if (:juxt.site/start-date ctx)
            (do
              ;; Don't log exceptions which are used to escape (e.g. 302, 401).
              (when (or (not (integer? status)) (>= status 500))
                (let [ex-data (->storable ex-data)]
                  (log/errorf e "%s: %s" (.getMessage e) (pr-str (dissoc ex-data :juxt.site/request-context)))))

              (error-response ctx e))

            (error-response
             req
             (ex-info "ExceptionInfo caught, but with an invalid request-context attached" {:juxt.site/request-context ctx} e)))))

      (catch Throwable t (respond-internal-error req t))
      (finally (org.slf4j.MDC/clear)))))

(defn wrap-check-error-handling
  "Ensure that on exceptions slip through the net."
  [h]
  (fn [req]
    (try
      (h req)
      (catch Throwable e
        ;; We're expecting the error handling to have fully resolved any
        ;; errors, it should not throw
        (log/error e "ERROR NOT HANDLED!")
        (throw e)))))

;; See https://portswigger.net/web-security/host-header and similar TODO: Have a
;; 'whitelist' in the config to check against - but this would require a reboot,
;; or some mechanism of freshening the whitelist without restart.
(def host-header-parser (rfc7230.decoders/host {}))

(defn assert-host [host]
  (try
    (host-header-parser (re/input host))
    (catch Exception e
      (throw (ex-info "Illegal host format" {:host host} e)))))

(defn new-request-id []
  (str "https://example.org/_site/requests/"
       (subs (util/hexdigest
              (.getBytes (str (java.util.UUID/randomUUID)) "US-ASCII")) 0 24)))

(defn normalize-path
  "Normalize path prior to constructing URL used for resource lookup. This is to
  avoid two equivalent URLs pointing to two different XTDB entities."
  [path]
  (cond
    (str/blank? path) "/"
    :else (-> path
              ;; Normalize (remove dot-segments from the path, see Section 6.2.2.3 of
              ;; RFC 3986)
              ((fn [path] (.toString (.normalize (URI. path)))))
              ;; TODO: Upcase any percent encodings: e.g. %2f -> %2F (as per Sections
              ;; 2.1 and 6.2.2.1 of RFC 3986)

              ;; TODO: Replace each space with '+'

              ;; TODO: Decode any non-reserved (:/?#[]@!$&'()*+,;=) percent-encoded
              ;; octets (see Section 6.2.2.2 of RFC 3986)
              )))

(defn http-scheme-normalize
  "Return a scheme-based normalized host, as per Section 6.2.3 of RFC 3986."
  [s scheme]
  (case scheme
    :http (if-let [[_ x] (re-matches #"(.*):80" s)] x s)
    :https (if-let [[_ x] (re-matches #"(.*):443" s)] x s)))

(defn wrap-initialize-request
  "Initialize request state."
  [h {:juxt.site/keys [xt-node uri-prefix] :as opts}]
  (assert xt-node)
  (fn [{:ring.request/keys [scheme path]
        :juxt.site/keys [uri] :as req}]

    (assert (not (and uri path)))

    (let [db (xt/db xt-node)
          req-id (new-request-id)

          uri (or uri
                  (let [host (or
                              (get-in req [:ring.request/headers "x-forwarded-host"])
                              (get-in req [:ring.request/headers "host"]))
                        scheme+authority
                        (or uri-prefix
                            (when host
                              (->
                               (let [{::rfc7230/keys [host]}
                                     (host-header-parser
                                      (re/input
                                       host))]
                                 (str (or (get-in req [:ring.request/headers "x-forwarded-proto"])
                                          (name scheme))
                                      "://" host))
                               (str/lower-case) ; See Section 6.2.2.1 of RFC 3986
                               (http-scheme-normalize scheme)))
                            (throw (ex-info "Failed to resolve uri-prefix" {:ctx :req})))]

                    ;; The scheme+authority is already normalized (by transforming to
                    ;; lower-case). The path, however, needs to be normalized here.
                    (str scheme+authority (normalize-path (:ring.request/path req)))))

          req (into req (merge
                         {:juxt.site/start-date (java.util.Date.)
                          :juxt.site/request-id req-id
                          :juxt.site/uri uri
                          :juxt.site/db db}
                         (dissoc opts :juxt.site/uri-prefix)))]

      ;; The Ring request map becomes the container for all state collected
      ;; along the request processing pathway.
      (h req))))

(defn wrap-ring-1-adapter
  "Given the presence of keywords from different origins, it helps that we
  distinguish Ring keywords from application keywords. Ring 2.0 provides a good
  set of namespaced keywords. This Ring middleware adapts a Ring 1.0 adapter to
  this set of keywords. See
  https://github.com/ring-clojure/ring/blob/2.0/SPEC-2.md for full details."
  [h]
  (fn [req]
    (let [mp {:body :ring.request/body
              :headers :ring.request/headers
              :request-method :ring.request/method
              :uri :ring.request/path
              :query-string :ring.request/query
              :protocol :ring.request/protocol
              :remote-addr :ring.request/remote-addr
              :scheme :ring.request/scheme
              :server-name :ring.request/server-name
              :server-port :ring.request/server-port
              :ssl-client-cert :ring.request/ssl-client-cert}]

      (-> (reduce-kv
           (fn [acc k v]
             (let [k2 (get mp k)]
               (cond-> acc k2 (assoc k2 v))))
           {} req)
          (h)
          (select-keys
           [:ring.response/status
            :ring.response/headers
            :ring.response/body])
          (set/rename-keys {:ring.response/status :status
                            :ring.response/headers :headers
                            :ring.response/body :body})))))

(defn wrap-store-request-in-request-cache [h]
  (fn [req]
    (let [req (h req)]
      (when-let [req-id (:juxt.site/request-id req)]
        (cache/put! cache/requests-cache req-id (->storable req)))
      req)))

(defn wrap-store-request [h]
  (fn [req]
    (let [req (h req)]
      (when-let [req-id (:juxt.site/request-id req)]
        (let [{:ring.request/keys [method] :juxt.site/keys [xt-node]} req]
          (when (or (= method :post) (= method :put))
            (xt/submit-tx
             xt-node
             [[:xtdb.api/put (-> req ->storable
                                (select-keys [:juxt.site/subject
                                              :juxt.site/date
                                              :juxt.site/uri
                                              :ring.request/method
                                              :ring.response/status])
                                (assoc :xt/id req-id
                                       :juxt.site/type "Request"))]]))))
      req)))

(defn wrap-log-request [h]
  (fn [req]
    (let [req (h req)]
      (assert req)
      (log-request! req)
      req)))

(defn wrap-healthcheck
  [h]
  (fn [req]
    (if (= "/_site/healthcheck" (:ring.request/path req))
      {:ring.response/status 200 :ring.response/body "Site OK!\r\n"}
      (h req))))

(defn service-available? [_]
  true)

(defn wrap-service-unavailable?
  "Check whether service is available, return 503 if it isn't."
  [h]
  (fn [req]
    (when-not (service-available? req)
      (throw
       (ex-info
        "Service unavailable"
        {:ring.response/status 503
         :ring.response/headers {"retry-after" "120"}
         :juxt.site/request-context req})))
    (h req)))

(defn wrap-bind-uri-to-mdc [h]
  (fn [{:juxt.site/keys [uri] :as req}]
    (org.slf4j.MDC/put "uri" uri)
    (try
      (h req)
      (finally (org.slf4j.MDC/remove "uri")))))

(defn make-pipeline
  "Make a pipeline of Ring middleware. Note, that each Ring middleware designates
  a processing stage. An interceptor chain (perhaps using Pedestal (pedestal.io)
  or Sieppari (https://github.com/metosin/sieppari) could be used. This is
  currently a synchronous chain but async could be supported in the future."
  [opts]
  [
   ;; Switch Ring requests/responses to Ring 2 namespaced keywords
   wrap-ring-1-adapter

   ;; Optional, helpful for AWS ALB
   wrap-healthcheck

   ;; Initialize the request by merging in some extra data
   #(wrap-initialize-request % opts)

   wrap-bind-uri-to-mdc

   wrap-service-unavailable?

   ;; Logging and store request
   wrap-log-request
   wrap-store-request
   wrap-store-request-in-request-cache

   ;; Security. This comes after error-handling so we can set the
   ;; correct CORS and security headers on error responses too.
   wrap-cors-headers
   wrap-security-headers

   ;; Error handling
   wrap-check-error-handling
   wrap-error-handling

   ;; 501
   wrap-method-not-implemented?

   ;; Locate resources
   wrap-locate-resource

   ;; Authenticate, some clues will be on the resource
   session-scope/wrap-session-scope

   wrap-http-authenticate

   ;; 405
   wrap-method-not-allowed?

   ;; We authorize the resource, prior to finding representations.
   operations/wrap-authorize-with-operation

   ;; Find representations and possibly do content negotiation
   wrap-find-current-representations
   wrap-negotiate-representation

   ;; Create initial response
   wrap-initialize-response

   ;; Methods (GET, PUT, POST, etc.)
   wrap-invoke-method])

(defn make-handler [opts]
  ((apply comp (make-pipeline opts)) identity))
