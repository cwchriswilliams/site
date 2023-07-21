;; Copyright © 2023, JUXT LTD.

(ns juxt.site.bb.tasks
  (:require
   [aero.core :as aero]
   [babashka.cli :as cli]
   [babashka.http-client :as http]
   [babashka.tasks :as tasks]
   [bblgum.core :as b]
   [cheshire.core :as json]
   [clj-yaml.core :as yaml]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [clojure.walk :refer [postwalk]]
   [juxt.site.bb.parameters :refer [resolve-parameters]]
   [juxt.site.bb.user-input :as input]
   [juxt.site.install.common-install-util :as ciu]))

(defmacro stderr [& body]
  `(binding [*out* *err*]
     ~@body))

(defn merge-global-opts [opts]
  (-> opts
      (update :alias assoc :p :profile)
      (update :coerce assoc :profile :keyword)))

(defn parse-opts
  "Take the :opts of the current task and add in globals"
  []
  (cli/parse-opts
   *command-line-args*
   (merge-global-opts (:opts (tasks/current-task)))))

(defn curl-config-file []
  (or
   (when (System/getenv "CURL_HOME")
     (io/file (System/getenv "CURL_HOME") ".curlrc"))
   (when (System/getenv "XDG_CONFIG_HOME")
     (io/file (System/getenv "XDG_CONFIG_HOME") ".curlrc"))
   (when (System/getenv "HOME")
     (io/file (System/getenv "HOME") ".curlrc"))))

(defn config-files []
  (for [dir [(io/file (System/getenv "XDG_CONFIG_HOME"))
             (io/file (System/getenv "HOME") ".config/site")]
        :when (and dir (.exists dir) (.isDirectory dir))
        file [(io/file dir "site-cli.edn")
              (io/file dir "site-cli.json")
              (io/file dir "site-cli.yaml")]]
    (.getAbsolutePath file)))

(defn config-file-task []
  (let [files (keep #(let [f (io/file %)]
                         (when (and (.exists f) (.isFile f))
                           (.getAbsolutePath f)))
                      (config-files))]
    (doseq [f files]
      (println f))))

(defn config-file []
  (let [candidates (config-files)
        files (keep #(let [f (io/file %)]
                         (when (and (.exists f) (.isFile f))
                           (.getAbsolutePath f)))
                      candidates)]
    (cond
      (empty? files) nil
      (and files (= 1 (count files))) (first files)
      :else (throw (ex-info (format "Too many (%d) possible configuration files" (count files))
                            {:candidates files})))))

(defn default-config
  "Return a default config which is useful for getting started"
  []
  {"admin-base-uri" "http://localhost:4911"
   "uri-map" {"https://auth.example.org" "http://localhost:4440"
              "https://data.example.org" "http://localhost:4444"}
   "installers-home" (str (System/getenv "SITE_HOME") "/installers")
   "client-credentials" {"ask-for-client-secret" true
                         "cache-client-secret" true}
   "curl" {"save-access-token-to-default-config-file" true}})

(defn profile [opts]
  (or
   (get opts :profile)
   (keyword (System/getenv "SITE_PROFILE"))
   :default))

(defn profile-task []
  (println (name (profile (parse-opts)))))

(defn config [opts]
  (if-let [config-file (config-file)]
    (condp re-matches config-file
      #".*\.edn" (aero/read-config
                  config-file
                  {:profile (profile opts)})
      #".*\.json" (json/parse-string (slurp config-file))
      #".*\.yaml" (yaml/parse-string (slurp config-file) {:keywords false})
      (throw (ex-info "Unrecognized config file" {:config-file config-file})))
    (default-config)))

(defn config-task []
  (let [{:keys [format] :as opts} (parse-opts)
        cfg (config opts)]
    (case format
      "edn" (pprint cfg)
      "json" (println (json/generate-string cfg {:pretty true}))
      "yaml" (println (yaml/generate-string cfg)))))

(defn ping []
  (let [opts (parse-opts)
        cfg (config opts)
        base-uri (get-in cfg ["uri-map" "https://data.example.org"])
        url (str base-uri "/_site/healthcheck")
        {:keys [status body]} (try
                                (http/get url {:throw false})
                                (catch java.net.ConnectException _
                                  {:status 0}))]
    (println "Checking" url)
    (cond
      (= status 0)
      (println "No response")
      (= status 200)
      (do
        (print "Response:" body)
        (.flush *out*))
      :else
      (do
        (println "Not OK")
        (println body)
        ;;(System/exit 1)
        ))))

(defn url-encode [s]
  (when s
    (java.net.URLEncoder/encode s)))

(defn ls []
  (let [{:keys [pattern] :as opts} (parse-opts)
        cfg (config opts)
        admin-base-uri (get cfg "admin-base-uri")]
    (if-not admin-base-uri
      (stderr (println "The admin-server is not reachable."))
      (doseq [res (json/parse-string
                   (:body
                    (http/get
                     (cond-> (str admin-base-uri "/resources")
                       pattern (str "?pattern=" (url-encode pattern)))
                     {"accept" "application/json"})))]
        (println res)))))

(defn find []
  (let [{:keys [pattern] :as opts} (parse-opts)
        cfg (config opts)
        admin-base-uri (get cfg "admin-base-uri")]
    (if-not admin-base-uri
      (stderr (println "The admin-server is not reachable."))
      (let [resources
            (json/parse-string
             (:body
              (http/get
               (cond-> (str admin-base-uri "/resources")
                 pattern (str "?pattern=" (url-encode pattern)))
               {"accept" "application/json"})))
            sw (java.io.StringWriter.)]

        (with-open [out (java.io.PrintWriter. sw)]
          (stderr (doseq [res resources] (println res))))
        (when-not (str/blank? (.toString sw))
          (let [{:keys [status result]}
                (b/gum {:cmd :filter
                        :opts {:placeholder "Select resource"
                               :fuzzy false
                               :indicator "⮕"
                               :indicator.foreground "#C72"
                               :match.foreground "#C72"}
                        :in (io/input-stream (.getBytes (.toString sw)))})]

            (when (zero? status)
              (let [resource (json/parse-string
                              (:body
                               (http/get
                                (str admin-base-uri "/resource?uri=" (url-encode (first result)))
                                {"accept" "application/json"})))]
                (pprint resource)))))))))

(defn- save-access-token [access-token]
  (let [opts (parse-opts)
        cfg (config opts)
        {access-token-file "access-token-file"
         save-access-token-to-default-config-file "save-access-token-to-default-config-file"}
        (get cfg "curl")]
    (cond
      save-access-token-to-default-config-file
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
                  (format "oauth2-bearer %s" access-token)))))
        (println "Access token saved to"
                 (str/replace
                  (.getAbsolutePath config-file)
                  (System/getenv "HOME") "$HOME")))

      access-token-file (spit access-token-file access-token)
      :else (println access-token))))

(defn cache-dir [opts]
  (let [parent-dir
        (or
         (when-let [dir (System/getenv "XDG_CACHE_HOME")] dir)
         (when-let [dir (System/getenv "HOME")] (io/file dir ".cache"))
         )
        fl (io/file parent-dir (str "site/" (name (get opts :profile :default))))]
    (.mkdirs (.getParentFile fl))
    fl))

(defn client-secret-file [opts client-id]
  (let [save-dir (io/file (cache-dir opts) "client-secrets")]
    (.mkdirs save-dir)
    (io/file save-dir client-id)))

(defn input-secret [client-id]
  (input/input {:header (format "Input client secret for %s" client-id)})
  #_(let [{status :status [secret] :result}
        (b/gum {:cmd :input
                :opts (cond-> {:header.foreground "#C72"
                               :prompt.foreground "#444"
                               :width 60
                               :header (format "Input client secret for %s" client-id)})})]
    ;; TODO: Check for status
    (println status)
    secret))

;; Not used?
(defn- client-secret
  "Only use when there is an admin server. We don't want to store client secrets on remote machines."
  [opts client-id]

  (let [cfg (config opts)
        _ (assert (not (get cfg "admin-base-uri")))

        secret-file (client-secret-file opts client-id)

        ask-for-client-secret? (get-in cfg ["client-credentials" "ask-for-client-secret"])
        cache-client-secret? (get-in cfg ["client-credentials" "cache-client-secret"])

        ;;_ (println "client-secret-file" client-secret-file " exists?" (.exists client-secret-file))
        secret (when (.exists secret-file)
                 (stderr
                   (println "Reading client secret from"
                            (str/replace
                             (.getAbsolutePath secret-file)
                             (System/getenv "HOME") "$HOME")))
                 (str/trim (slurp secret-file)))

        secret
        (or secret
            (when ask-for-client-secret?
              (let [{status :status [secret] :result}
                    (b/gum {:cmd :input
                            :opts (cond-> {:header.foreground "#C72"
                                           :prompt.foreground "#444"
                                           :width 60
                                           :header (format "Input client secret for %s" client-id)})})]
                (when cache-client-secret?
                  (stderr
                    (println "Writing client_secret to"
                             (str/replace
                              (.getAbsolutePath secret-file)
                              (System/getenv "HOME") "$HOME")))
                  (spit secret-file secret))
                secret)))]

    secret))

(defn forget-client-secret []
  (let [{:keys [client-id] :as opts} (parse-opts)
        secret-file (client-secret-file opts client-id)]
    (if (.exists secret-file)
      (do
        (println "Deleting" (.getAbsolutePath secret-file))
        (io/delete-file secret-file))
      (println "No such file:" (.getAbsolutePath secret-file)))))

(defn- retrieve-token
  [cfg]
  (let [{curl "curl" access-token-file "access-token"} cfg
        {save-access-token-to-default-config-file "save-access-token-to-default-config-file"} curl
        token (cond
                (and access-token-file save-access-token-to-default-config-file)
                (throw (ex-info "Ambiguous configuration" {}))

                save-access-token-to-default-config-file
                (let [curl-config-file (curl-config-file)]
                  (when (and (.exists curl-config-file) (.isFile curl-config-file))
                    (last (keep (comp second #(re-matches #"oauth2-bearer\s+(.+)" %)) (line-seq (io/reader curl-config-file))))))

                access-token-file
                (when (and (.exists access-token-file) (.isFile access-token-file))
                  (slurp access-token-file)))]
    token))

(defn request-token
  "Acquire an access-token. Remote only."
  [{:keys [client-id grant-type] :as opts}]
  (let [cfg (config opts)
        auth-base-uri (get-in cfg ["uri-map" "https://auth.example.org"])
        token-endpoint (str auth-base-uri "/oauth/token")
        grant-type (cond
                     grant-type grant-type
                     (or (:username opts) (:password opts)) "password"
                     :else "client_credentials")]
    (stderr
     (println
      (format "Requesting access-token from %s\n with grant-type %s" token-endpoint grant-type)))
    (case grant-type
      "password"
      (let [{:keys [username password]} opts
            password (or password (input/input {:header (format "Input password for %s" username)
                                                :password true}))
            {:keys [status body]}
            (http/post
             token-endpoint
             {:headers {"content-type" "application/x-www-form-urlencoded"}
              :body (format "grant_type=%s&username=%s&password=%s&client_id=%s"
                            "password" username password client-id)
              :throw false})]

        (when-not username
          (throw (ex-info "username must be given" {})))

        (when-not password
          (throw (ex-info "password must be given" {})))

        (case status
          200 (get (json/parse-string body) "access_token")
          (print status body)))

      "client_credentials"
      (let [secret (or
                    (:client-secret opts)
                    (input-secret client-id))
            _ (when-not secret
                (println "No client-secret found")
                (System/exit 1))
            {:keys [status body]}
            (http/post
             token-endpoint
             {:basic-auth [client-id secret]
              :form-params {"grant_type" "client_credentials"}
              :throw false})]
        (case status
          200 (get (json/parse-string body) "access_token")
          (print status body))))))

(defn request-token-task [opts]
  (when-let [token (request-token opts)]
    (save-access-token token)))

(defn check-token []
  (let [opts (parse-opts)
        cfg (config opts)
        token (retrieve-token cfg)]
    (if-not token
      (stderr (println "Hint: Try requesting an access-token (site request-token)"))
      (let [auth-base-uri (get-in cfg ["uri-map" "https://auth.example.org"])
            {introspection-status :status introspection-body :body}
            (http/post
             (str auth-base-uri "/oauth/introspect")
             {:headers {"authorization" (format "Bearer %s" token)}
              :form-params {"token" token}
              :throw false})

            zone-id (java.time.ZoneId/systemDefault)

            claim-time
            (fn [seconds]
              (.toString
               (java.time.ZonedDateTime/ofInstant
                (java.time.Instant/ofEpochSecond seconds)
                zone-id)))

            claims
            (when (and (= introspection-status 200) introspection-body)
              (let [claims (json/parse-string introspection-body)]
                (-> claims
                    (assoc "issued-at" (claim-time (get claims "iat")))
                    (assoc "expires-at" (claim-time (get claims "exp"))))))]
        (println
         (json/generate-string
          (cond-> {"access-token" token
                   "introspection-status" introspection-status}
            claims
            (assoc "claims" claims))
          {:pretty true}))))))

(defn authorization [cfg]
  (format "Bearer %s" (retrieve-token cfg)))

(defn api-request-json [path]
  (let [opts (parse-opts)
        cfg (config opts)
        data-base-uri (get-in cfg ["uri-map" "https://data.example.org"])
        endpoint (str data-base-uri path)
        {:keys [status body]} (http/get
                               endpoint
                               {:headers {"content-type" "application/json"
                                          "authorization" (authorization cfg)}
                                :throw false})]
    (case status
      200 (print body)
      401 (stderr
            (print status body)
            (println "Hint: Try requesting an access-token (site request-token)"))
      (stderr
        (print status body)
        (.flush *out*)))))

(defn whoami [{:keys [verbose] :as opts}]
  (let [path "/_site/whoami"]
    (if-not verbose
      (let [cfg (config opts)
            data-base-uri (get-in cfg ["uri-map" "https://data.example.org"])
            ;; TODO: There is a problem with babashka.http-client's
            ;; handling of the accept header :(
            ;; As a workaround, we go direct to the EDN representation.
            endpoint (str data-base-uri (str path ".edn"))
            {:keys [status body]} (http/get
                                   endpoint
                                   {:headers {"authorization" (authorization cfg)}
                                    :throw false})]
        (case status
          200 (let [edn (clojure.edn/read-string body)
                    whoami (or
                            (get-in edn [:juxt.site/subject :juxt.site/user])
                            (get-in edn [:juxt.site/subject :juxt.site/application]))]
                (if whoami
                  (println whoami)
                  (stderr
                    (println
                     "No valid subject (hint: try requesting an access token with site request-token)"))))
          401 (do
                (print status body)
                (println "Hint: Try requesting an access-token (site request-token)"))
          (do
            (print status body)
            (.flush *out*))))
      ;; Verbose
      (api-request-json path))))

(defn api-endpoints []
  (api-request-json "/_site/api-endpoints"))

(defn users []
  (api-request-json "/_site/users"))

;; This can be replaced by jo, curl and jq
#_(defn add-user [{:keys [username password] :as opts}]
    (let [{resource-server "resource_server"} (config)
          api-endpoint (str (get resource-server "base_uri") "/_site/users")

          token (retrieve-token)
          ;; Couldn't we just request the token?
          _ (when-not token
              (throw (ex-info "No bearer token" {})))

          cleartext-password
          (when password
            (let [{input-status :status [cleartext-password] :result}
                  (b/gum {:cmd :input
                          :opts (cond-> {:header.foreground "#C72"
                                         :prompt.foreground "#444"
                                         :password true
                                         :width 60
                                         :header (format "Input client secret for %s" username)})})]
              (if-not (zero? input-status)
                (throw (ex-info "Password input failed" {}))
                cleartext-password)))

          request-body (->
                        (cond-> opts
                          (:password opts) (dissoc :password)
                          cleartext-password (assoc :password cleartext-password))
                        json/generate-string)

          {post-status :status response-body :body}
          (http/post
           api-endpoint
           {:headers {"content-type" "application/json"
                      "authorization" (format "Bearer %s" token)}
            :body request-body})]


      (println "post-status:" post-status)
      (println "request-body:" request-body)))

#_(defn jwks []
  (let [{authorization-server "authorization_server"} (config)
        {data-base-uri "base_uri"} authorization-server
        url (str data-base-uri "/.well-known/jwks.json")
        {:keys [status body]} (http/get url)]
    (cond
      (= status 200)
      (println body)
      :else
      (prn (json/generate-string "Not OK")))))

(memoize
 (defn bundles [cfg]
   (let [bundles-file (io/file (get cfg "installers-home") "bundles.edn")]
     (when-not (.exists bundles-file)
       (throw (ex-info "bundles.edn does not exist" {:bundles-file (.getAbsolutePath bundles-file)}))
       )
     (edn/read-string
      (slurp (io/file (System/getenv "SITE_HOME") "installers/bundles.edn"))))))

(defn uri-map-replace
  "Replace URIs in string, taking substitutions from the given uri-map."
  [s uri-map]
  (str/replace
   s
   #"(https?://.*?example.org)([\p{Alnum}-]+)*"
   (fn [[_ host path]] (str (get uri-map host host) path))))

(defn apply-uri-map [uri-map installers]
  (postwalk
   (fn walk-fn [node]
     (cond
       (string? node) (uri-map-replace node uri-map)
       :else node))
   installers))

(defn- bundle* [cfg {:juxt.site/keys [description parameters installers]} opts]
  (let [uri-map (get cfg "uri-map")

        parameters
        (resolve-parameters (apply-uri-map uri-map parameters) opts)

        installers (apply-uri-map uri-map installers)

        installer-map (ciu/unified-installer-map
                       (io/file (get cfg "installers-home"))
                       uri-map)

        installers-seq (ciu/installer-seq installer-map parameters installers)]
    installers-seq))

(defn installers-seq [{:keys [bundle] :as opts}]
  (let [cfg (config opts)
        bundles (bundles cfg)

        bundle-name (or bundle
                       (let [{:keys [status result]}
                             (b/gum {:cmd :filter
                                     :opts {:placeholder "Select resource"
                                            :fuzzy false
                                            :indicator "⮕"
                                            :indicator.foreground "#C72"
                                            :match.foreground "#C72"}
                                     :in (io/input-stream (.getBytes (str/join "\n"(sort (keys bundles)))))})]
                         (when-not (zero? status)
                           (throw (ex-info "Error, non-zero status" {})))
                         (first result)))

        installers-seq (bundle* cfg (get bundles bundle-name) opts)]

    ;; JSON - not yet installable
    #_(println (json/generate-string installers-seq {:pretty true}))

    ;; EDN
    installers-seq

    ;; The reason to use a zip file is to allow future extensions
    ;; where the zip file can contain binary data, such as images used
    ;; in login screens. Site is very capable at storing and serving
    ;; binary assets. It can also contain signatures, such as
    ;; install.edn.sig.
    #_(with-open [out (new java.util.zip.ZipOutputStream (new java.io.FileOutputStream outfile))]
        (.putNextEntry out (new java.util.zip.ZipEntry "install.edn"))
        (doseq [op installers-seq
                :let [edn {:juxt.site/operation-uri (get-in op [:juxt.site/init-data :juxt.site/operation-uri])
                           :juxt.site/operation-arg (get-in op [:juxt.site/init-data :juxt.site/input])}
                      content (str (with-out-str (pprint edn)) "\r\n")
                      bytes (.getBytes content "UTF-8")]]
          (.write out bytes 0 (count bytes)))
        (.closeEntry out))))

(defn bundle [opts]
  (pprint (installers-seq opts)))

(defn random-string [size]
  (apply str
         (map char
              (repeatedly size
                          (fn []
                            (rand-nth
                             (concat
                              (range (int \A) (inc (int \Z)))
                              (range (int \a) (inc (int \z)))
                              (range (int \0) (inc (int \9))))))))))

(defn request-client-secret [admin-base-uri client-id]
  (assert admin-base-uri)
  (let [client-details
        (json/parse-string
         (:body
          (http/get
           (str admin-base-uri "/applications/" client-id)
           {"accept" "application/json"})))]
    (get client-details "juxt.site/client-secret")))

(defn print-or-save-client-secret [{:keys [client-id save] :as opts}]

  ;; TODO: The repl (client-secret) must also have a where clause to
  ;; restrict us to the right auth-server! Otherwise we'll be
  ;; potentially fishing out the first of a bundle of client-secrets!

  (let [cfg (config opts)
        admin-base-uri (get cfg "admin-base-uri")]
    (if-not admin-base-uri
      (stderr (println "The admin-server is not reachable."))
      (let [client-secret (request-client-secret admin-base-uri client-id)
            secret-file (client-secret-file opts client-id)]
        (binding [*out* (if save (io/writer secret-file) *out*)]
          (println client-secret))
        (when save
          (stderr
            (println "Written client secret to" (.getAbsolutePath secret-file))))))))

;; Equivalent to: curl -X POST http://localhost:4911/reset
(defn reset
  "Delete ALL resources from a Site instance"
  []
  (let [opts (parse-opts)
        cfg (config opts)
        admin-base-uri (get cfg "admin-base-uri")]
    (if-not admin-base-uri
      (stderr (println "Cannot reset. The admin-server is not reachable."))
      (when (input/confirm "Factory reset and delete ALL resources?")
        (println "(To cancel, type Control-C)")
        (print "Deleting resources in ")
        (.flush *out*)
        (Thread/sleep 200)
        (doseq [n (reverse (map inc (range 3)))]
          (print (str n "... "))
          (.flush *out*)
          (Thread/sleep 1000))
        (println)
        (println "Requesting removal of all resources")
        (let [{:keys [status body]}
              (http/post (str admin-base-uri "/reset"))]
          ;; print not println, as the body should be terminated in a CRLF
          (print status body))))))

(defn install [{:keys [resources-uri access-token]} installers-seq]
  (assert resources-uri)
  (let [{:keys [status body]}
        (http/post
         resources-uri
         {:headers (cond-> {"content-type" "application/edn"}
                     access-token (assoc "authorization" (format "Bearer %s" access-token)))
          :body (pr-str installers-seq)
          :throw false})]
    (case status
      200 (print body)
      (print status body))))

(defn install-bundles [{named-bundles :bundles :as opts}]
  (let [cfg (config opts)
        bundles (bundles cfg)]
    (doseq [[bundle-name params] named-bundles
            :let [bundle (get bundles bundle-name)
                  param-str (str/join ", " (for [[k v] params] (str (name k) "=" v)))
                  title (get bundle :juxt.site/title bundle-name)]]
      (println
       (if (str/blank? param-str)
         (format "Installing: %s" title)
         (format "Installing: %s with %s" title param-str)))
      (install
       opts
       (installers-seq (into opts (into params {:bundle bundle-name})))))))

(defn init [opts]
  (let [cfg (config opts)
        admin-base-uri (get cfg "admin-base-uri")]
    (if-not admin-base-uri
      (stderr (println "Cannot init. The admin-server is not reachable."))
      (do
        (install-bundles
         (assoc
          opts
          :resources-uri
          (str admin-base-uri "/resources")
          :bundles
          [["juxt/site/bootstrap" {}]
           ;; Support the creation of JWT bearer tokens
           ["juxt/site/oauth-token-endpoint" {}]
           ;; Install a keypair to sign JWT bearer tokens
           ["juxt/site/keypair" {:kid (random-string 16)}]
           ;; Install the required APIs
           ["juxt/site/api-operations" {}]
           ["juxt/site/resources-api" {}]
           ["juxt/site/whoami-api" {}]
           ["juxt/site/users-api" {}]
           ["juxt/site/endpoints-api" {}]
           ;; RFC 7662 token introspection
           ["juxt/site/oauth-introspection-endpoint" {}]
           ;; Register the clients
           ["juxt/site/system-client" {:client-id "site-cli"}]
           ["juxt/site/system-client" {:client-id "insite"}]]))

        ;; Delete any stale client-secret files
        (doseq [client-id ["site-cli" "insite"]
                :let [secret-file (client-secret-file opts client-id)]]
          ;; TODO: Replace with babashka.fs
          (.delete secret-file))

        (println)
        (println "You should now continue to configure your Site instance,")
        (println "using one of the following methods:")
        (println)

        (println (format "A. Proceed to https://insite.juxt.site?client-secret=%s" (request-client-secret admin-base-uri "insite")))
        (println " or ")
        (println (format "B. Continue with this site tool, acquiring an access token with:" ))
        ;; TODO: We could pipe this to '| xclip -selection clipboard'
        (println (format "site request-token --client-secret %s" (request-client-secret admin-base-uri "site-cli")))))))

(defn new-keypair []
  (let [opts (parse-opts)
        cfg (config opts)
        data-base-uri (get-in cfg ["uri-map" "https://data.example.org"])]
    (install-bundles
     (assoc
      opts
      :resources-uri
      (str data-base-uri "/_site/resources")
      :access-token
      (retrieve-token cfg)
      :bundles
      [;; Install a new keypair to sign JWT bearer tokens
       ["juxt/site/keypair" {:kid (random-string 16)}]]))))

;; Create alice
;; jo -- -s username=alice fullname="Alice Carroll" password=foobar | curl --json @- http://localhost:4444/_site/users
;; site register-user --username alice --fullname "Alice Carroll" --password $(gum input --password)
(defn register-user [opts]
  (let [cfg (config opts)
        base-uri (get-in cfg ["uri-map" "https://data.example.org"])
        {:keys [status body]}
        (http/post
         (str base-uri "/_site/users")
         {:headers {"content-type" "application/json"
                    "accept" "application/json"
                    "authorization" (authorization cfg)}
          :body (json/generate-string opts {:pretty true})
          :throw false})]
    (case status
      200 (print body)
      (print status body))))

;; Grant alice a role
;; jo -- -s juxt.site/user=http://localhost:4444/_site/users/alice juxt.site/role=http://localhost:4444/_site/roles/Admin | curl --json @- http://localhost:4440/operations/assign-role
;; site assign-user-role --username alice --role Admin
(defn assign-user-role [opts]
  (let [cfg (config opts)
        auth-base-uri (get-in cfg ["uri-map" "https://auth.example.org"])
        data-base-uri (get-in cfg ["uri-map" "https://data.example.org"])
        {:keys [status body]}
        (http/post
         (str auth-base-uri "/operations/assign-role")
         {:headers {"content-type" "application/edn"
                    "authorization" (authorization cfg)}
          :body (pr-str
                 {:juxt.site/user (str data-base-uri "/_site/users/" (:username opts))
                  :juxt.site/role (str data-base-uri "/_site/roles/" (:role opts))})
          :throw false})]
    (case status
      200 (print body)
      (print status body))))

;; Login as alice
;; site request-token --username alice --password $(gum input --password) --grant-type password

;; Create bob
;; site register-user --username bob --fullname "Bob Stewart" --password $(gum input --password)
;; equivalent to:
;; jo -- -s username=bob fullname="Bob Stewart" password=foobar | curl --json @- http://localhost:4444/_site/users

;; Login as bob
;; site request-token --username bob --password foobar --grant-type password
