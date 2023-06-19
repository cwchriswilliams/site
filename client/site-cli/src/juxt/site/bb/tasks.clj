;; Copyright © 2023, JUXT LTD.

(ns juxt.site.bb.tasks
  (:require
   [babashka.http-client :as http]
   [babashka.cli :as cli]
   [bblgum.core :as b]
   [clojure.java.io :as io]
   [clj-yaml.core :as yaml]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn curl-config-file []
  (or
   (when (System/getenv "CURL_HOME")
     (io/file (System/getenv "CURL_HOME") ".curlrc"))
   (when (System/getenv "XDG_CONFIG_HOME")
     (io/file (System/getenv "XDG_CONFIG_HOME") ".curlrc"))
   (when (System/getenv "HOME")
     (io/file (System/getenv "HOME") ".curlrc"))))

(memoize
 (defn config []
   (or
    (let [config-file (io/file (System/getenv "HOME") ".config/site/site-tool.edn")]
      (when (.exists config-file)
        (edn/read-string (slurp config-file))))

    (let [config-file (io/file (System/getenv "HOME") ".config/site/site-tool.json")]
      (when (.exists config-file)
        (json/parse-string (slurp config-file))))

    (let [config-file (io/file (System/getenv "HOME") ".config/site/site-tool.yaml")]
      (when (.exists config-file)
        (yaml/parse-string (slurp config-file) {:keywords false})))

    {"empty_configuration" true})))

(defn config-as-json []
  (println (json/generate-string (config) {:pretty true})))

(defn ping []
  (let [{resource-server "resource_server"} (config)
        {data-base-uri "base_uri"} resource-server
        url (str data-base-uri "/_site/healthcheck")
        {:keys [status body]} (http/get url)]
    (println "Checking" url)
    (cond
      (= status 200)
      (println "Response:" body)
      :else
      (do
        (println "Not OK")
        (println body)))))

(defn- save-bearer-token [access-token]
  (let [config (config)
        {bearer-token-file "bearer_token_file"
         save-bearer-token-to-default-config-file "save_bearer_token_to_default_config_file"}
        (get config "curl")]
    (cond
      save-bearer-token-to-default-config-file
      (let [config-file (curl-config-file)
            lines (if (.exists config-file)
                    (with-open [rdr (io/reader config-file)]
                      (into [] (line-seq rdr)))
                    [])
            new-lines
            (mapv (fn [line]
                    (if (re-matches #"oauth2-bearer\s+.+" line)
                      (format "oauth2-bearer %s" access-token)
                      line)) lines)]

        (spit config-file
              (clojure.string/join
               (System/getProperty "line.separator")
               (cond-> new-lines
                 (= lines new-lines)
                 (conj
                  "# This was added by site request-token"
                  (format "oauth2-bearer %s" access-token))))))

      bearer-token-file (spit bearer-token-file access-token)
      :else (println access-token))))

(defn cache-dir []
  (or
   (when (System/getenv "XDG_CACHE_HOME")
     (let [dir (io/file (System/getenv "XDG_CACHE_HOME") "site")]
       (.mkdirs dir)
       dir))
   (when (System/getenv "HOME")
     (let [dir (io/file (System/getenv "HOME") ".cache/site")]
       (.mkdirs dir)
       dir))))

(defn- get-client-secret [client-id]
  (let [config (config)

        client-secret-file (io/file (cache-dir) (str "client-secrets/" client-id))

        ask-for-client-secret? (get-in config ["client_credentials" "ask_for_client_secret"])
        cache-client-secret? (get-in config ["client_credentials" "cache_client_secret"])

        ;;_ (println "client-secret-file" client-secret-file " exists?" (.exists client-secret-file))
        client-secret (when (.exists client-secret-file)
                        (println "Reading client-secret from" (.getAbsolutePath client-secret-file))
                        (str/trim (slurp client-secret-file)))

        client-secret
        (or client-secret
            (when ask-for-client-secret?
              (let [{status :status [client-secret] :result}
                    (b/gum {:cmd :input
                            :opts (cond-> {:header.foreground "#C72"
                                           :prompt.foreground "#444"
                                           :width 60
                                           :header (format "Input client secret for %s" client-id)})})]
                (when cache-client-secret?
                  (println "Writing client_secret to" (.getAbsolutePath client-secret-file))
                  (spit client-secret-file client-secret))
                client-secret)))]

    client-secret))

(defn forget-client-secret []
  (let [cli-opts {:args->opts [:client-id]
                  :require [:client-id]
                  :exec-args {:client-id "site-cli"}
                  :validate {:client-id {:pred string?}}}
        {:keys [client-id]} (cli/parse-opts *command-line-args* cli-opts)
        client-secret-file (io/file (cache-dir) (str "client-secrets/" client-id))]
    (if (.exists client-secret-file)
      (do
        (println "Deleting" (.getAbsolutePath client-secret-file))
        (io/delete-file client-secret-file))
      (println "No such file:" (.getAbsolutePath client-secret-file)))))

(defn check-token []
  (let [{{auth-base-uri "base_uri"} "authorization_server"
         curl "curl"} (config)

        {bearer-token-file "bearer_token_file"
         save-bearer-token-to-default-config-file "save_bearer_token_to_default_config_file"} curl

        token (cond
                save-bearer-token-to-default-config-file
                (let [curl-config-file (curl-config-file)]
                  (when-not (and (.exists curl-config-file) (.isFile curl-config-file))
                    (System/exit 1))
                  (last (keep (comp second #(re-matches #"oauth2-bearer\s+(.+)" %)) (line-seq (io/reader curl-config-file)))))
                bearer-token-file
                (do
                  (when-not (and (.exists bearer-token-file) (.isFile bearer-token-file))
                    (System/exit 1))
                  (slurp bearer-token-file)))
        _ (when-not token (System/exit 1))

        client-secret (get-client-secret "site-cli")
        _ (when-not client-secret
            (println "No client-secret found")
            (System/exit 1))

        {introspection-status :status introspection-body :body}
        (http/post
         (str auth-base-uri "/oauth/introspect")
         {:basic-auth ["site-cli" client-secret]
          :form-params {"token" token}
          :throw false})

        claims (when introspection-body
                 (json/parse-string introspection-body))
        zone-id (java.time.ZoneId/systemDefault)
        claim-time (fn [claim]
                     (java.time.ZonedDateTime/ofInstant
                      (java.time.Instant/ofEpochSecond (get claims claim))
                      zone-id))
        issued-at (.toString (claim-time "iat"))
        expires-at (.toString (claim-time "exp"))]

    (println
     (json/generate-string
      (cond-> {"bearer-token" token}
        (= introspection-status 200)
        (assoc "introspection" {"claims" claims
                                "issued_at" issued-at
                                "expires_at" expires-at})
        (not= introspection-status 200)
        (assoc "introspection" {"status" introspection-status}))
      {:pretty true}))))

(defn request-token-with-client-secret []
  (let [cli-opts {:args->opts [:client-id]
                  :require [:client-id]
                  :exec-args {:client-id "site-cli"}
                  :validate {:client-id {:pred string?}}}
        {:keys [client-id]} (cli/parse-opts *command-line-args* cli-opts)
        {authorization-server "authorization_server"} (config)
        {auth-base-uri "base_uri"} authorization-server
        token-endpoint (str auth-base-uri "/oauth/token")
        client-secret (get-client-secret client-id)
        _ (when-not client-secret
            (println "No client_secret found")
            (System/exit 1))
        {:keys [status body]}
        (http/post
         token-endpoint
         {:basic-auth [client-id client-secret]
          :form-params {"grant_type" "client_credentials"}
          :throw false})]
    (case status
      200 (let [{access-token "access_token"
                 ;; TODO: Can we use this expires-in to calculate when our token will expire?
                 expires-in "expires_in"}
                (json/parse-string body)]
            (when access-token
              (save-bearer-token access-token))
            (println "Access token saved, expires in" expires-in "seconds"))
      400 (let [{error "error"
                 desc "error_description"} (json/parse-string body)]
            (println error)
            (println desc))
      (println "ERROR, status" status ", body:" body))))

(defn request-token-with-password []
  (let [{authorization-server "authorization_server"} (config)

        {auth-base-uri "base_uri"
         username "username"
         password "password"
         client-id "client_id"}

        authorization-server

        token-endpoint (str auth-base-uri "/oauth/token")

        {:keys [status body]}
        (http/post
         token-endpoint
         {:headers {"content-type" "application/x-www-form-urlencoded"}
          :body (format "grant_type=%s&username=%s&password=%s&client_id=%s"
                        "password" username password client-id)})

        _ (when-not (= status 200)
            (println "Not OK, status was" status)
            (System/exit 1))

        {access-token "access_token"} (json/parse-string body)]

    (when access-token
      (save-bearer-token access-token))))

(defn add-user []
  (throw (ex-info "Unsupported currently" {})))

(defn jwks []
  (let [{authorization-server "authorization_server"} (config)
        {data-base-uri "base_uri"} authorization-server
        url (str data-base-uri "/.well-known/jwks.json")
        {:keys [status body]} (http/get url)]
    (cond
      (= status 200)
      (println body)
      :else
      (prn (json/generate-string "Not OK")))))