;; Copyright © 2022, JUXT LTD.

(ns juxt.pass.whoami-test
  (:require
   [clojure.edn :as edn]
   [edn-query-language.core :as eql]
   [juxt.site.alpha.eql-datalog-compiler :as eqlc]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is are testing]]
   [java-http-clj.core :as hc]
   [juxt.pass.alpha :as-alias pass]
   [juxt.pass.openid :as openid]
   [juxt.pass.session-scope :as session-scope]
   [juxt.pass.user :as user]
   [juxt.pass.form-based-auth :as form-based-auth]
   [juxt.site.alpha :as-alias site]
   [juxt.site.alpha.init :as init]
   [juxt.site.alpha.repl :as repl]
   [juxt.site.bootstrap :as bootstrap]
   [juxt.test.util :refer [*handler* *xt-node* with-fixtures with-resources assoc-session-token]]
   [ring.util.codec :as codec]
   [xtdb.api :as xt]
   [juxt.reap.alpha.regex :as re]))

(with-fixtures
  (let [dependency-graph
        {"https://example.org/user-identities/alice"
         {:deps #{::init/system
                  "https://example.org/~alice"}
          :create (fn [{:keys [id]}]
                    ;; TODO: Make this data rather than calling a function! (The
                    ;; intention here is to demote this graphs to data;
                    (init/do-action
                     (init/substitute-actual-base-uri "https://example.org/subjects/system")
                     (init/substitute-actual-base-uri "https://example.org/actions/put-basic-user-identity")
                     (init/substitute-actual-base-uri
                      {:xt/id "https://example.org/user-identities/alice"
                       :juxt.pass.alpha/user "https://example.org/~alice"
                       :juxt.pass.alpha/username "alice"
                       :juxt.pass.alpha/password "garden"})))}

         "https://example.org/~alice"
         {:deps #{::init/system
                  ::user/all-actions
                  ::user/default-permissions}
          :create (fn [{:keys [id]}]
                    (user/put-user!
                     {:id id :username "alice" :name "Alice"}))}

         "https://example.org/actions/whoami"
         {:deps #{::init/system}
          :create (fn [{:keys [id]}]
                    (init/do-action
                     (init/substitute-actual-base-uri "https://example.org/subjects/system")
                     (init/substitute-actual-base-uri "https://example.org/actions/create-action")
                     {:xt/id id

                      ;; NOTE: This means: Use the action to extract part of the
                      ;; resource's state.  Actions are used to extract
                      ;; protected data, particularly part of the state of a
                      ;; resource.
                      :juxt.site.alpha/state
                      {:juxt.site.alpha.sci/program
                       (pr-str
                        ;; TODO: Use a pull syntax
                        '{:subject
                          (xt/pull
                           '[* {:juxt.pass.alpha/user-identity [* {:juxt.pass.alpha/user [*]}]}]
                           (:xt/id (:juxt.pass.alpha/subject *ctx*)))})}

                      :juxt.pass.alpha/rules
                      '[
                        [(allowed? subject resource permission)
                         [subject :juxt.pass.alpha/user-identity id]
                         [id :juxt.pass.alpha/user user]
                         [permission :juxt.pass.alpha/user user]]]}))}

         "https://example.org/permissions/alice/whoami"
         {:deps #{::init/system
                  "https://example.org/actions/whoami"}
          :create (fn [{:keys [id]}]
                    (juxt.site.alpha.init/do-action
                     (init/substitute-actual-base-uri "https://example.org/subjects/system")
                     (init/substitute-actual-base-uri "https://example.org/actions/grant-permission")
                     (init/substitute-actual-base-uri
                      {:xt/id id
                       :juxt.pass.alpha/action "https://example.org/actions/whoami"
                       :juxt.pass.alpha/purpose nil
                       :juxt.pass.alpha/user "https://example.org/~alice"})))}

         "https://example.org/whoami"
         {:deps #{::init/system
                  "https://example.org/actions/whoami"}
          :create (fn [{:keys [id]}]
                    (init/put!
                     (init/substitute-actual-base-uri
                      {:xt/id id
                       :juxt.site.alpha/methods
                       {:get {:juxt.pass.alpha/actions #{"https://example.org/actions/whoami"}}}})))}

         "https://example.org/whoami.json"
         {:deps #{::init/system
                  "https://example.org/actions/whoami"}
          :create (fn [{:keys [id]}]
                    (init/put!
                     (init/substitute-actual-base-uri
                      {:xt/id id
                       :juxt.site.alpha/methods
                       {:get {:juxt.pass.alpha/actions #{"https://example.org/actions/whoami"}}}
                       :juxt.site.alpha/variant-of "https://example.org/whoami"
                       :juxt.http.alpha/content-type "application/json"
                       :juxt.http.alpha/respond
                       {:juxt.site.alpha.sci/program
                        (pr-str
                         '(let [content (jsonista.core/write-value-as-string *state*)]
                            (-> *ctx*
                                (assoc :ring.response/body content)
                                (update :ring.response/headers assoc "content-length" (count (.getBytes content)))
                                )))}})))}

         "https://example.org/whoami.html"
         {:deps #{::init/system
                  "https://example.org/actions/whoami"}
          :create (fn [{:keys [id]}]
                    (init/put!
                     (init/substitute-actual-base-uri
                      {:xt/id id
                       :juxt.site.alpha/methods
                       {:get {:juxt.pass.alpha/actions #{"https://example.org/actions/whoami"}}}
                       :juxt.site.alpha/variant-of "https://example.org/whoami"
                       :juxt.http.alpha/content-type "text/html;charset=utf-8"
                       :juxt.http.alpha/respond
                       {:juxt.site.alpha.sci/program
                        (pr-str
                         '(let [content (format "<h1>Hello World! state is %s</h1>\n" (pr-str *state*))]
                            (-> *ctx*
                                (assoc :ring.response/body content)
                                (update :ring.response/headers assoc "content-length" (count (.getBytes content)))
                                )))}}

                      )))}}]

    (with-resources
      ^{:dependency-graphs
        #{session-scope/dependency-graph
          user/dependency-graph
          form-based-auth/dependency-graph
          dependency-graph}}
      #{"https://site.test/login"
        "https://site.test/user-identities/alice"
        "https://site.test/whoami"
        "https://site.test/whoami.json"
        "https://site.test/whoami.html"
        "https://site.test/permissions/alice/whoami"
        })

    (let [result
          (form-based-auth/login-with-form!
           *handler*
           "username" "alice"
           "password" "garden"
           :juxt.site.alpha/uri "https://site.test/login")

          session-token (:juxt.pass.alpha/session-token result)
          _ (assert session-token)]

      (->
       (*handler*
        (->
         {:juxt.site.alpha/uri "https://site.test/whoami"
          :ring.request/method :get
          :ring.request/headers {"accept" "application/json"}
          }
         (assoc-session-token session-token)))

       (select-keys
        [:ring.response/status
         :ring.response/headers
         :ring.response/body]))

      ;; NOTE: Actions should emit DATA, not form. It is the data that an action
      ;; is protecting and managing, not a particular view of it.

      ;; Where's the subject resource?
      ;; Do a eqlc compile on
      #_(let [db (xt/db *xt-node*)]
          (eqlc/compile-ast
           db
           (eql/query->ast
            '[:subject])))

      #_(let [db (xt/db *xt-node*)
              subject
              (first
               (disj
                (set (repl/ls-type "https://meta.juxt.site/pass/subject"))
                "https://site.test/subjects/system"))]
          (xt/pull db '[* {:juxt.pass.alpha/user-identity [* {:juxt.pass.alpha/user [*]}]}] subject))

      )))

;; Note: If we try to login (with basic), we'll won't need to user 'put' (which will
;; lead to dangerously brittle tests if/when we change the structure of internal
;; documents like sessions and session-tokens).

;; TODO
;; Login alice with basic (ensuring session scope exists)
;; Passing the session-token as a cookie, call the /whoami resource.
;; Build the functionality of GET /whoami into the action (in the prepare part of the transaction)