;; Copyright © 2022, JUXT LTD.

(ns juxt.site.test-helpers.oauth
  (:require
   [clojure.string :as str]
   [clojure.pprint :refer [pprint]]
   [juxt.site.util :refer [make-nonce]]
   [juxt.test.util :refer [*handler*]]
   [malli.core :as malli]
   [ring.util.codec :as codec]))

(defn authorization-request
  "Create a request that can be sent to the authorization_endpoint of an
  authorization server"
  [uri {client-id "client_id"
        scope "scope"
        state :state}]
  {:ring.request/method :get
   :juxt.site/uri uri
   :ring.request/query
   (codec/form-encode
    (cond->
        {"response_type" "token"
         "client_id" client-id
         "state" state}
        scope (assoc "scope" (codec/url-encode (str/join " " scope)))))})

(defn authorize-response!
  "Authorize response"
  [uri args]
  (let [request (authorization-request uri (assoc args :state (make-nonce 10)))]
    (*handler* request)))

(malli/=>
 authorize-response!
 [:=> [:cat
       [:string]
       [:map
        ["client_id" :string]
        ["scope" {:optional true} [:sequential :string]]]]
  [:map
   ["access_token" {:optional true} :string]
   ["error" {:optional true} :string]]])

(defn authorize!
  "Authorize a client, and return decoded fragment parameters as a string->string map"
  [uri args]
  (let [response (authorize-response! uri args)
        _ (case (:ring.response/status response)
            (302 303) :ok
            400 (throw (ex-info "Client error" (assoc args :response response)))
            403 (throw (ex-info "Forbidden to authorize" (assoc args :response response)))
            (throw (ex-info "Unexpected error" (assoc args :response response))))

        location-header (-> response :ring.response/headers (get "location"))

        [_ _ encoded-access-token]
        (re-matches #"https://(.*?)/.*?#(.*)" location-header)]

    (when-not encoded-access-token
      (throw (ex-info "No access-token fragment" {:response response})))

    (codec/form-decode encoded-access-token)))

(malli/=>
 authorize!
 [:=> [:cat
       [:string]
       [:map
;;        ^{:doc "to authenticate with authorization server"} [:juxt.site/session-token :string]
        ["client_id" :string]
        ["scope" {:optional true} [:sequential :string]]]]
  [:map
   ["access_token" {:optional true} :string]
   ["error" {:optional true} :string]]])
