;; Copyright © 2022, JUXT LTD.

(ns juxt.book
  (:require
   [juxt.http.alpha :as-alias http]
   [crypto.password.bcrypt :as password]
   [juxt.pass.alpha :as-alias pass]
   [juxt.site.alpha :as-alias site]
   [clojure.walk :refer [postwalk]]
   [clojure.string :as str]
   [malli.core :as m]
   [juxt.site.alpha.repl :refer [base-uri put! install-do-action-fn! do-action make-application-doc make-application-authorization-doc make-access-token-doc encrypt-password]]
   [juxt.site.alpha.util :refer [as-hex-str random-bytes]]))

(defn substitute-actual-base-uri [form]
  (postwalk
   (fn [s]
     (cond-> s
       (string? s) (str/replace "https://site.test" (base-uri)))
     )
   form))

(defn put-user! []
  ;; tag::install-user![]
  (put! {:xt/id "https://site.test/users/alice"
         :juxt.site.alpha/type "https://meta.juxt.site/pass/user"
         :name "Alice" ; <1>
         :role #{"User" "Administrator"} ; <2>
         })
  ;; end::install-user![]
  )

(defn put-user-identity! []
  ;; tag::install-user-identity![]
  (put! {:xt/id "https://site.test/user-identities/alice"
         :juxt.site.alpha/type "https://meta.juxt.site/pass/user-identity"
         :juxt.pass.alpha/user "https://site.test/users/alice"})
  ;; end::install-user-identity![]
  )

(defn put-subject! []
  ;; tag::install-subject![]
  (put! {:xt/id "https://site.test/subjects/repl-default"
         :juxt.site.alpha/type "https://meta.juxt.site/pass/subject"
         :juxt.pass.alpha/user-identity "https://site.test/user-identities/alice"})
  ;; end::install-subject![]
  )

(defn install-create-action! []
  ;; tag::install-create-action![]
  (put!
   {:xt/id "https://site.test/actions/create-action" ; <1>
    :juxt.site.alpha/type "https://meta.juxt.site/pass/action"
    :juxt.pass.alpha/scope "write:admin"
    :juxt.pass.alpha.malli/args-schema
    [:tuple
     [:map
      [:xt/id [:re "https://site.test/actions/(.+)"]] ; <2>
      [:juxt.site.alpha/type [:= "https://meta.juxt.site/pass/action"]]
      [:juxt.pass.alpha/rules [:vector [:vector :any]]]]]

    :juxt.pass.alpha/process
    [
     [:juxt.pass.alpha.process/update-in [0] ; <3>
      'merge {:juxt.site.alpha/type "https://meta.juxt.site/pass/action"}]
     [:juxt.pass.alpha.malli/validate] ; <4>
     [:xtdb.api/put]] ; <5>

    :juxt.pass.alpha/rules
    '[
      [(allowed? permission subject action resource) ; <6>
       [subject :juxt.pass.alpha/user-identity id]
       [id :juxt.pass.alpha/user user]
       [user :role role]
       [permission :role role]]]})
  ;; end::install-create-action![]
  )

(defn book-install-do-action-fn! []
  ;; tag::install-do-action-fn![]
  (install-do-action-fn!)
  ;; end::install-do-action-fn![]
  )

(defn permit-create-action! []
  ;; tag::permit-create-action![]
  (put!
   {:xt/id "https://site.test/permissions/administrators/create-action" ; <1>
    :juxt.site.alpha/type "https://meta.juxt.site/pass/permission" ; <2>
    :juxt.pass.alpha/action "https://site.test/actions/create-action" ; <3>
    :juxt.pass.alpha/purpose nil ; <4>
    :role "Administrator" ; <5>
    })
  ;; end::permit-create-action![]
  )

(defn create-grant-permission-action! []
  ;; tag::create-grant-permission-action![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/grant-permission"
    :juxt.site.alpha/type "https://meta.juxt.site/pass/action"
    :juxt.pass.alpha/scope "write:admin"

    :juxt.pass.alpha.malli/args-schema
    [:tuple
     [:map
      [:xt/id [:re "https://site.test/permissions/(.+)"]]
      [:juxt.site.alpha/type [:= "https://meta.juxt.site/pass/permission"]]
      [:juxt.pass.alpha/action [:re "https://site.test/actions/(.+)"]]
      [:juxt.pass.alpha/purpose [:maybe :string]]]]

    :juxt.pass.alpha/process
    [
     [:juxt.pass.alpha.process/update-in [0]
      'merge {:juxt.site.alpha/type "https://meta.juxt.site/pass/permission"}]
     [:juxt.pass.alpha.malli/validate]
     [:xtdb.api/put]]

    :juxt.pass.alpha/rules
    '[
      [(allowed? permission subject action resource)
       [subject :juxt.pass.alpha/user-identity id]
       [id :juxt.pass.alpha/user user]
       [user :role role]
       [permission :role role]]]})
  ;; end::create-grant-permission-action![]
  )

(defn permit-grant-permission-action! []
  ;; tag::permit-grant-permission-action![]
  (put!
   {:xt/id "https://site.test/permissions/administrators/grant-permission"
    :juxt.site.alpha/type "https://meta.juxt.site/pass/permission"
    :role "Administrator"
    :juxt.pass.alpha/action "https://site.test/actions/grant-permission"
    :juxt.pass.alpha/purpose nil})
  ;; end::permit-grant-permission-action![]
  )

;; Users Revisited

(defn create-action-put-user! []
  ;; tag::create-action-put-user![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/put-user"
    :juxt.pass.alpha/scope "write:users"

    :juxt.pass.alpha.malli/args-schema
    [:tuple
     [:map
      [:xt/id [:re "https://site.test/users/.*"]] ; <1>
      [:juxt.site.alpha/type [:= "https://meta.juxt.site/pass/user"]] ; <2>
      ]]

    :juxt.pass.alpha/process
    [
     [:juxt.pass.alpha.process/update-in [0]
      'merge {:juxt.site.alpha/type "https://meta.juxt.site/pass/user" ; <3>
              :juxt.site.alpha/methods ; <4>
              {:get {:juxt.pass.alpha/actions #{"https://site.test/actions/get-user"}}
               :head {:juxt.pass.alpha/actions #{"https://site.test/actions/get-user"}}
               :options {}}}]
     [:juxt.pass.alpha.malli/validate]
     [:xtdb.api/put]]

    :juxt.pass.alpha/rules
    '[
      [(allowed? permission subject action resource) ; <5>
       [subject :juxt.pass.alpha/user-identity id]
       [id :juxt.pass.alpha/user user]
       [user :role role]
       [permission :role role]]]})
  ;; end::create-action-put-user![]
  )

(defn grant-permission-to-invoke-action-put-user! []
  ;; tag::grant-permission-to-invoke-action-put-user![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/administrators/put-user"
    :role "Administrator"
    :juxt.pass.alpha/action "https://site.test/actions/put-user"
    :juxt.pass.alpha/purpose nil})
  ;; end::grant-permission-to-invoke-action-put-user![]
  )

(defn create-action-put-user-identity! []
  ;; tag::create-action-put-user-identity![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/put-user-identity"
    :juxt.pass.alpha/scope "write:users"

    :juxt.pass.alpha.malli/args-schema
    [:tuple
     [:map
      [:xt/id [:re "https://site.test/.*"]]
      [:juxt.pass.alpha/user [:re "https://site.test/users/.+"]]
      [:juxt.pass.jwt/iss {:optional true} [:re "https://.+"]]
      [:juxt.pass.jwt/sub {:optional true} [:string {:min 1}]]]]

    :juxt.pass.alpha/process
    [
     [:juxt.pass.alpha.process/update-in
      [0] 'merge
      {:juxt.site.alpha/type "https://meta.juxt.site/pass/user-identity"
       :juxt.site.alpha/methods
       {:get {:juxt.pass.alpha/actions #{"https://site.test/actions/get-user-identity"}}
        :head {:juxt.pass.alpha/actions #{"https://site.test/actions/get-user-identity"}}
        :options {}}}]
     [:juxt.pass.alpha.malli/validate]
     [:xtdb.api/put]]

    :juxt.pass.alpha/rules
    '[
      [(allowed? permission subject action resource)
       [subject :juxt.pass.alpha/user-identity id]
       [id :juxt.pass.alpha/user user]
       [user :role role]
       [permission :role role]]]})
  ;; end::create-action-put-user-identity![]
  )

(defn grant-permission-to-invoke-action-put-user-identity! []
  ;; tag::grant-permission-to-invoke-action-put-user-identity![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/administrators/put-user-identity"
    :role "Administrator"
    :juxt.pass.alpha/action "https://site.test/actions/put-user-identity"
    :juxt.pass.alpha/purpose nil})
  ;; end::grant-permission-to-invoke-action-put-user-identity![]
  )

(defn create-action-put-subject! []
  ;; tag::create-action-put-subject![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/put-subject"
    ;;:juxt.pass.alpha/scope "write:users"

    :juxt.pass.alpha.malli/args-schema
    [:tuple
     [:map
      [:xt/id [:re "https://site.test/.*"]]
      [:juxt.pass.alpha/user-identity [:re "https://site.test/user-identities/.+"]]
      ]]

    :juxt.pass.alpha/process
    [
     [:juxt.pass.alpha.process/update-in
      [0] 'merge
      {:juxt.site.alpha/type "https://meta.juxt.site/pass/subject"}]
     [:juxt.pass.alpha.malli/validate]
     [:xtdb.api/put]]

    :juxt.pass.alpha/rules
    '[
      [(allowed? permission subject action resource)
       [subject :juxt.pass.alpha/user-identity id]
       [id :juxt.pass.alpha/user user]
       [user :role role]
       [permission :role role]]]})
  ;; end::create-action-put-subject![]
  )

(defn grant-permission-to-invoke-action-put-subject! []
  ;; tag::grant-permission-to-invoke-action-put-subject![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/administrators/put-subject"
    :role "Administrator"
    :juxt.pass.alpha/action "https://site.test/actions/put-subject"
    :juxt.pass.alpha/purpose nil})
  ;; end::grant-permission-to-invoke-action-put-subject![]
  )

;; Hello World!

(defn create-action-put-immutable-public-resource! []
  ;; tag::create-action-put-immutable-public-resource![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/put-immutable-public-resource"
    :juxt.pass.alpha/scope "write:resource" ; <1>

    :juxt.pass.alpha.malli/args-schema
    [:tuple
     [:map
      [:xt/id [:re "https://site.test/.*"]]]]

    :juxt.pass.alpha/process
    [
     [:juxt.pass.alpha.process/update-in
      [0] 'merge
      {:juxt.site.alpha/methods ; <2>
       {:get {::pass/actions #{"https://site.test/actions/get-public-resource"}}
        :head {::pass/actions #{"https://site.test/actions/get-public-resource"}}
        :options {::pass/actions #{"https://site.test/actions/get-options"}}}}]

     [:juxt.pass.alpha.malli/validate]
     [:xtdb.api/put]]

    :juxt.pass.alpha/rules
    '[
      [(allowed? permission subject action resource) ; <3>
       [subject :juxt.pass.alpha/user-identity id]
       [id :juxt.pass.alpha/user user]
       [user :role role]
       [permission :role role]]]})
  ;; end::create-action-put-immutable-public-resource![]
  )

(defn grant-permission-to-invoke-action-put-immutable-public-resource! []
  ;; tag::grant-permission-to-invoke-action-put-immutable-public-resource![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/administrators/put-immutable-public-resource"
    :role "Administrator"
    :juxt.pass.alpha/action "https://site.test/actions/put-immutable-public-resource"
    :juxt.pass.alpha/purpose nil})
  ;; end::grant-permission-to-invoke-action-put-immutable-public-resource![]
  )

(defn create-action-get-public-resource! []
  ;; tag::create-action-get-public-resource![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/get-public-resource"
    :juxt.pass.alpha/scope "read:resource" ; <1>

    :juxt.pass.alpha/rules
    '[
      [(allowed? permission subject action resource)
       [permission :xt/id "https://site.test/permissions/public-resources-to-all"] ; <2>
       ]]})
  ;; end::create-action-get-public-resource![]
  )

(defn grant-permission-to-invoke-get-public-resource! []
  ;; tag::grant-permission-to-invoke-get-public-resource![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/public-resources-to-all"
    :juxt.pass.alpha/action "https://site.test/actions/get-public-resource"
    :juxt.pass.alpha/purpose nil})
  ;; end::grant-permission-to-invoke-get-public-resource![]
  )

(defn create-hello-world-resource! []
  ;; tag::create-hello-world-resource![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/put-immutable-public-resource"
   {:xt/id "https://site.test/hello"
    :juxt.http.alpha/content-type "text/plain"
    :juxt.http.alpha/content "Hello World!\r\n"})
  ;; end::create-hello-world-resource![]
  )


;; Representations

(defn create-hello-world-html-representation! []
  (eval
   (substitute-actual-base-uri
    (quote
     ;; tag::create-hello-world-html-representation![]
     (do-action
      "https://site.test/subjects/repl-default"
      "https://site.test/actions/put-immutable-public-resource"
      {:xt/id "https://site.test/hello.html" ; <1>
       :juxt.http.alpha/content-type "text/html;charset=utf-8" ; <2>
       :juxt.http.alpha/content "<h1>Hello World!</h1>\r\n" ; <3>
       :juxt.site.alpha/variant-of "https://site.test/hello" ; <4>
       })
     ;; end::create-hello-world-html-representation![]
     ))))


;; Templating

(defn create-put-template-action! []
  (eval
   (substitute-actual-base-uri
    (quote
     ;; tag::create-put-template-action![]
     (do-action
      "https://site.test/subjects/repl-default"
      "https://site.test/actions/create-action"
      {:xt/id "https://site.test/actions/put-template"
       :juxt.pass.alpha/scope "write:resource"

       :juxt.pass.alpha.malli/args-schema
       [:tuple
        [:map
         [:xt/id [:re "https://site.test/templates/.*"]]]]

       :juxt.pass.alpha/process
       [
        [:juxt.pass.alpha.process/update-in
         [0] 'merge
         {:juxt.site.alpha/methods {}}]
        [:juxt.pass.alpha.malli/validate]
        [:xtdb.api/put]]

       :juxt.pass.alpha/rules
       '[
         [(allowed? permission subject action resource)
          [permission :juxt.pass.alpha/user-identity i]
          [subject :juxt.pass.alpha/user-identity i]]]})
     ;; end::create-put-template-action![]
     ))))

(defn grant-permission-to-invoke-action-put-template! []
  (eval
   (substitute-actual-base-uri
    (quote
     ;; tag::grant-permission-to-invoke-action-put-template![]
     (do-action
      "https://site.test/subjects/repl-default"
      "https://site.test/actions/grant-permission"
      {:xt/id "https://site.test/permissions/alice/put-template"
       :juxt.pass.alpha/user "https://site.test/users/alice"
       :juxt.pass.alpha/action #{"https://site.test/actions/put-template"}
       :juxt.pass.alpha/purpose nil})
     ;; end::grant-permission-to-invoke-action-put-template![]
     ))))

(defn create-hello-world-html-template! []
  (eval
   (substitute-actual-base-uri
    (quote
     ;; tag::create-hello-world-html-template![]
     (do-action
      "https://site.test/subjects/repl-default"
      "https://site.test/actions/put-template"
      {:xt/id "https://site.test/templates/hello.html"
       :juxt.http.alpha/content-type "text/html;charset=utf-8"
       :juxt.http.alpha/content "<h1>Hello {audience}!</h1>\r\n"})
     ;; end::create-hello-world-html-template![]
     ))))

(defn create-hello-world-with-html-template! []
  (eval
   (substitute-actual-base-uri
    (quote
     ;; tag::create-hello-world-with-html-template![]
     (do-action
      "https://site.test/subjects/repl-default"
      "https://site.test/actions/put-immutable-public-resource"
      {:xt/id "https://site.test/hello-with-template.html"
       :juxt.site.alpha/template "https://site.test/templates/hello.html"
       })
     ;; end::create-hello-world-with-html-template![]
     ))))

;; Protecting Resources

(defn create-action-put-immutable-protected-resource! []
  ;; tag::create-action-put-immutable-protected-resource![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/put-immutable-protected-resource"
    :juxt.pass.alpha/scope "write:resource"

    :juxt.pass.alpha.malli/args-schema
    [:tuple
     [:map
      [:xt/id [:re "https://site.test/.*"]]]]

    :juxt.pass.alpha/process
    [
     [:juxt.pass.alpha.process/update-in
      [0] 'merge
      {:juxt.site.alpha/methods
       {:get {::pass/actions #{"https://site.test/actions/get-protected-resource"}} ; <1>
        :head {::pass/actions #{"https://site.test/actions/get-protected-resource"}}
        :options {::pass/actions #{"https://site.test/actions/get-options"}}}}]

     [:juxt.pass.alpha.malli/validate]
     [:xtdb.api/put]]

    :juxt.pass.alpha/rules
    '[
      [(allowed? permission subject action resource) ; <2>
       [subject :juxt.pass.alpha/user-identity id]
       [id :juxt.pass.alpha/user user]
       [permission :role role]
       [user :role role]]]})
  ;; end::create-action-put-immutable-protected-resource![]
  )

(defn grant-permission-to-put-immutable-protected-resource! []
  ;; tag::grant-permission-to-put-immutable-protected-resource![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/administrators/put-immutable-protected-resource"
    :role "Administrator"
    :juxt.pass.alpha/action "https://site.test/actions/put-immutable-protected-resource"
    :juxt.pass.alpha/purpose nil})
  ;; end::grant-permission-to-put-immutable-protected-resource![]
  )

(defn create-action-get-protected-resource! []
  ;; tag::create-action-get-protected-resource![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/get-protected-resource"
    :juxt.pass.alpha/scope "read:resource"

    :juxt.pass.alpha/rules
    '[
      [(allowed? permission subject action resource)
       [subject :juxt.pass.alpha/user-identity id]
       [id :juxt.pass.alpha/user user]
       [permission :juxt.pass.alpha/user user] ; <1>
       [permission :juxt.site.alpha/uri resource] ; <2>
       ]]})
  ;; end::create-action-get-protected-resource![]
  )

;; Protection Spaces

(defn create-action-put-protection-space! []
  ;; tag::create-action-put-protection-space![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/put-protection-space"
    :juxt.pass.alpha/scope "write:admin"

    :juxt.pass.alpha.malli/args-schema
    [:tuple
     [:map
      [:xt/id [:re "https://site.test/protection-spaces/(.+)"]]
      [:juxt.site.alpha/type [:= "https://meta.juxt.site/pass/protection-space"]]
      [:juxt.pass.alpha/canonical-root-uri [:re "https?://[^/]*"]]
      [:juxt.pass.alpha/realm {:optional true} [:string {:min 1}]]
      [:juxt.pass.alpha/auth-scheme [:enum "Basic" "Bearer"]]
      [:juxt.pass.alpha/authentication-scope [:string {:min 1}]]]]

    :juxt.pass.alpha/process
    [
     [:juxt.pass.alpha.process/update-in [0]
      'merge {:juxt.site.alpha/type "https://meta.juxt.site/pass/protection-space"}]
     [:juxt.pass.alpha.malli/validate]
     [:xtdb.api/put]]

    :juxt.pass.alpha/rules
    '[
      [(allowed? permission subject action resource)
       [subject :juxt.pass.alpha/user-identity id]
       [id :juxt.pass.alpha/user user]
       [permission :role role]
       [user :role role]]]})
  ;; end::create-action-put-protection-space![]
  )

(defn grant-permission-to-put-protection-space! []
  ;; tag::grant-permission-to-put-protection-space![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/administrators/put-protection-space"
    :role "Administrator"
    :juxt.pass.alpha/action "https://site.test/actions/put-protection-space"
    :juxt.pass.alpha/purpose nil})
  ;; end::grant-permission-to-put-protection-space![]
  )

;; HTTP Basic Auth

(defn create-resource-protected-by-basic-auth! []
  ;; tag::create-resource-protected-by-basic-auth![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/put-immutable-protected-resource"
   {:xt/id "https://site.test/protected-by-basic-auth/document.html"
    :juxt.http.alpha/content-type "text/html;charset=utf-8"
    :juxt.http.alpha/content "<p>This is a protected message that those authorized are allowed to read.</p>"
    })
  ;; end::create-resource-protected-by-basic-auth![]
  )

(defn grant-permission-to-resource-protected-by-basic-auth! []
  ;; tag::grant-permission-to-resource-protected-by-basic-auth![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/alice/protected-by-basic-auth/document.html"
    :juxt.pass.alpha/action "https://site.test/actions/get-protected-resource"
    :juxt.pass.alpha/user "https://site.test/users/alice"
    :juxt.site.alpha/uri "https://site.test/protected-by-basic-auth/document.html"
    :juxt.pass.alpha/purpose nil
    })
  ;; end::grant-permission-to-resource-protected-by-basic-auth![]
  )

(defn put-basic-protection-space! []
  ;; tag::put-basic-protection-space![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/put-protection-space"
   {:xt/id "https://site.test/protection-spaces/basic/wonderland"

    :juxt.pass.alpha/canonical-root-uri "https://site.test"
    :juxt.pass.alpha/realm "Wonderland" ; optional

    :juxt.pass.alpha/auth-scheme "Basic"
    :juxt.pass.alpha/authentication-scope "/protected-by-basic-auth/.*" ; regex pattern
    })
  ;; end::put-basic-protection-space![]
)

(defn put-basic-auth-user-identity! []
  ;; tag::put-basic-auth-user-identity![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/put-user-identity"
   {:xt/id "https://site.test/user-identities/alice/basic"
    :juxt.pass.alpha/user "https://site.test/users/alice"
    ;; Perhaps all user identities need this?
    :juxt.pass.alpha/canonical-root-uri "https://site.test"
    :juxt.pass.alpha/realm "Wonderland"
    ;; Basic auth will only work if these are present
    :juxt.pass.alpha/username "alice"
    :juxt.pass.alpha/password-hash (encrypt-password "garden")})
  ;; end::put-basic-auth-user-identity![]
  )

;; HTTP Bearer Auth

(defn create-resource-protected-by-bearer-auth! []
  ;; tag::create-resource-protected-by-bearer-auth![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/put-immutable-protected-resource"
   {:xt/id "https://site.test/protected-by-bearer-auth/document.html"
    :juxt.http.alpha/content-type "text/html;charset=utf-8"
    :juxt.http.alpha/content "<p>This is a protected message that those authorized are allowed to read.</p>"
    })
  ;; end::create-resource-protected-by-bearer-auth![]
  )

(defn grant-permission-to-resource-protected-by-bearer-auth! []
  ;; tag::grant-permission-to-resource-protected-by-bearer-auth![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/alice/protected-by-bearer-auth/document.html"
    :juxt.pass.alpha/action "https://site.test/actions/get-protected-resource"
    :juxt.pass.alpha/user "https://site.test/users/alice"
    :juxt.site.alpha/uri "https://site.test/protected-by-bearer-auth/document.html"
    :juxt.pass.alpha/purpose nil
    })
  ;; end::grant-permission-to-resource-protected-by-bearer-auth![]
  )

(defn put-bearer-protection-space! []
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/put-protection-space"
   {:xt/id "https://site.test/protection-spaces/bearer/wonderland"

    :juxt.pass.alpha/canonical-root-uri "https://site.test"
    :juxt.pass.alpha/realm "Wonderland" ; optional

    :juxt.pass.alpha/auth-scheme "Bearer"
    :juxt.pass.alpha/authentication-scope "/protected-by-bearer-auth/.*" ; regex pattern
    }))

;; Session Scopes Preliminaries

(defn create-action-put-session-scope! []
  ;; tag::create-action-put-session-scope![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/put-session-scope"
    :juxt.pass.alpha/scope "write:admin"

    :juxt.pass.alpha.malli/args-schema
    [:tuple
     [:map
      [:xt/id [:re "https://site.test/session-scopes/(.+)"]]
      [:juxt.site.alpha/type [:= "https://meta.juxt.site/pass/session-scope"]]
      [:juxt.pass.alpha/cookie-domain [:re "https?://[^/]*"]]
      [:juxt.pass.alpha/cookie-path [:re "/.*"]]
      [:juxt.pass.alpha/login-uri [:re "https?://[^/]*"]]]]

    :juxt.pass.alpha/process
    [
     [:juxt.pass.alpha.process/update-in [0]
      'merge {:juxt.site.alpha/type "https://meta.juxt.site/pass/session-scope"}]
     [:juxt.pass.alpha.malli/validate]
     [:xtdb.api/put]]

    :juxt.pass.alpha/rules
    '[
      [(allowed? permission subject action resource)
       [subject :juxt.pass.alpha/user-identity id]
       [id :juxt.pass.alpha/user user]
       [permission :role role]
       [user :role role]]]})
  ;; end::create-action-put-session-scope![]
  )

(defn grant-permission-to-put-session-scope! []
  ;; tag::grant-permission-to-put-session-scope![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/administrators/put-session-scope"
    :role "Administrator"
    :juxt.pass.alpha/action "https://site.test/actions/put-session-scope"
    :juxt.pass.alpha/purpose nil})
  ;; end::grant-permission-to-put-session-scope![]
  )

;; Session Scope Example

(defn create-resource-protected-by-session-scope! []
  ;; tag::create-resource-protected-by-session-scope![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/put-immutable-protected-resource"
   {:xt/id "https://site.test/protected-by-session-scope/document.html"
    :juxt.http.alpha/content-type "text/html;charset=utf-8"
    :juxt.http.alpha/content "<p>This is a protected message that is only visible when sending the correct session header.</p>"
    })
  ;; end::create-resource-protected-by-session-scope![]
  )

(defn grant-permission-to-resource-protected-by-session-scope! []
  ;; tag::grant-permission-to-resource-protected-by-session-scope![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/alice/protected-by-session-scope/document.html"
    :juxt.pass.alpha/action "https://site.test/actions/get-protected-resource"
    :juxt.pass.alpha/user "https://site.test/users/alice"
    :juxt.site.alpha/uri "https://site.test/protected-by-session-scope/document.html"
    :juxt.pass.alpha/purpose nil
    })
  ;; end::grant-permission-to-resource-protected-by-session-scope![]
  )

(defn create-session-scope! []
  ;; tag::create-session-scope![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/put-session-scope"
   {:xt/id "https://site.test/session-scopes/example"
    :juxt.pass.alpha/cookie-name "id"
    :juxt.pass.alpha/cookie-domain "https://site.test"
    :juxt.pass.alpha/cookie-path "/protected-by-session-scope/"
    :juxt.pass.alpha/login-uri "https://site.test/login"})
    ;; end::create-session-scope![]
  )

(defn create-login-form! []
  ;; tag::create-login-form![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/put-immutable-public-resource"
   {:xt/id "https://site.test/login"
    :juxt.http.alpha/content-type "text/html;charset=utf-8"
    :juxt.http.alpha/content
    "
<html>
<head>
<link rel='icon' href='data:,'>
</head>
<body>
<form method=POST>
<p>
Username: <input name=username type=text>
</p>
<p>
Password: <input name=password type=password>
</p>
<p>
<input type=submit value=Login>
</p>
</form>
</body>
</html>
\r\n"})
    ;; end::create-login-form![]
  )

(defn create-login-resource! []
  ;; tag::create-login-resource![]
  (put!
   {:xt/id "https://site.test/login"
    :juxt.site.alpha/methods
    {:post
     {:juxt.pass.alpha/actions #{"https://site.test/actions/login"}}}})
  ;; end::create-login-resource![]
  )

(defn create-action-login! []
  ;; tag::create-action-login![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/login"

    :juxt.pipe.alpha/quotation
    (list
     [:validate
      [:map
       ["username" [:string {:min 1}]]
       ["password" [:string {:min 1}]]]]

     :dup

     [:push "username"]
     :of

     :swap
     [:push "password"]
     :of
     :swap

     ;; We now have a stack with: <user> <password>

     [:find-matching-identity-on-password-query
      {:username-in-identity-key :juxt.pass.alpha/username
       :password-hash-in-identity-key :juxt.pass.alpha/password-hash}]

     :juxt.pipe.alpha.xtdb/q :first :first

     ;; If nil then return 400

     [:if*
      (list

       [:push :juxt.pass.alpha/user-identity]
       :swap :associate

       [:push ::site/type "https://meta.juxt.site/pass/subject"]
       :set-at

       ;; Make subject
       [:push :xt/id]
       10 :random-bytes :as-hex-string
       "https://site.test/subjects/" :str
       :set-at

       ;; Create the session, linked to the subject
       :dup [:push :xt/id] :of
       [:push ::pass/subject] :swap :associate

       ;; Now we're good to wrap up the subject in a tx-op
       :swap :xtdb.api/put :swap

       [:push :xt/id]
       16 :make-nonce
       "https://site.test/sessions/" :str
       :set-at
       [:push ::site/type "https://meta.juxt.site/pass/session"]
       :set-at

       :dup [:push :xt/id] :of
       [:push ::pass/session] :swap :associate

       :swap :xtdb.api/put :swap

       16 :make-nonce
       :swap
       :over
       [:push ::pass/session-token]
       :swap
       :set-at
       :swap
       "https://site.test/session-tokens/" :str
       [:push :xt/id]
       :swap
       :set-at
       [:push ::site/type "https://meta.juxt.site/pass/session-token"] :set-at
       :xtdb.api/put)

      (list
       "Login failed"
       {:ring.response/status 400} ;; Respond with a 400 status
       :ex-info :throw
       )])

    :juxt.pass.alpha/rules
    '[
      [(allowed? permission subject action resource)
       [permission :xt/id]]]})
  ;; end::create-action-login![]
  )

(defn grant-permission-to-invoke-action-login! []
  ;; tag::permit-action-login![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/login"
    :juxt.pass.alpha/action "https://site.test/actions/login"
    :juxt.pass.alpha/purpose nil})
  ;; end::permit-action-login![]
  )

;; Applications

(defn create-action-put-application! []
  ;; tag::create-action-put-application![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/put-application"
    :juxt.pass.alpha/scope "write:application"
    :juxt.pass.alpha.malli/args-schema
    [:tuple
     [:map
      [:xt/id [:re "https://site.test/applications/(.+)"]]
      [:juxt.site.alpha/type [:= "https://meta.juxt.site/pass/application"]]
      [:juxt.pass.alpha/oauth-client-id [:string {:min 10}]]
      [:juxt.pass.alpha/oauth-client-secret [:string {:min 16}]]]]
    :juxt.pass.alpha/process
    [
     [:juxt.pass.alpha.process/update-in [0]
      'merge {:juxt.site.alpha/type "https://meta.juxt.site/pass/application"}]
     [:juxt.pass.alpha.malli/validate]
     [:xtdb.api/put]
     ]
    :juxt.pass.alpha/rules
    '[[(allowed? permission subject action resource)
       [id :juxt.pass.alpha/user user]
       [subject :juxt.pass.alpha/user-identity id]
       [user :role role]
       [permission :role role]]]})
  ;; end::create-action-put-application![]
  )

(defn grant-permission-to-invoke-action-put-application! []
  ;; tag::grant-permission-to-invoke-action-put-application![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/administrators/put-application"
    :role "Administrator"
    :juxt.pass.alpha/action "https://site.test/actions/put-application"
    :juxt.pass.alpha/purpose nil})
  ;; end::grant-permission-to-invoke-action-put-application![]
  )

(defn create-action-authorize-application! []
  ;; tag::create-action-authorize-application![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/authorize-application"
    :juxt.pass.alpha.malli/args-schema
    [:tuple
     [:map
      [:xt/id [:re "https://site.test/authorizations/(.+)"]]
      [:juxt.site.alpha/type [:= "https://meta.juxt.site/pass/authorization"]]
      [:juxt.pass.alpha/user [:re "https://site.test/users/(.+)"]]
      [:juxt.pass.alpha/application [:re "https://site.test/applications/(.+)"]]
      ;; A space-delimited list of permissions that the application requires.
      [:juxt.pass.alpha/scope {:optional true} :string]]]
    :juxt.pass.alpha/process
    [
     [:juxt.pass.alpha.process/update-in [0]
      'merge {:juxt.site.alpha/type "https://meta.juxt.site/pass/authorization"}]
     [:juxt.pass.alpha.malli/validate]
     [:xtdb.api/put]
     ]
    :juxt.pass.alpha/rules
    '[[(allowed? permission subject action resource)
       [id :juxt.pass.alpha/user user]
       [subject :juxt.pass.alpha/user-identity id]
       [user :role role]
       [permission :role role]]]})
  ;; end::create-action-authorize-application![]
  )

(defn grant-permission-to-invoke-action-authorize-application! []
  ;; tag::grant-permission-to-invoke-action-authorize-application![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/users/authorize-application"
    :role "User"
    :juxt.pass.alpha/action "https://site.test/actions/authorize-application"
    :juxt.pass.alpha/purpose nil})
  ;; end::grant-permission-to-invoke-action-authorize-application![]
  )

(defn create-action-issue-access-token! []
  ;; tag::create-action-issue-access-token![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/issue-access-token"
    :juxt.pass.alpha.malli/args-schema
    [:tuple
     [:map
      [:xt/id [:re "https://site.test/access-tokens/(.+)"]]
      [:juxt.site.alpha/type [:= "https://meta.juxt.site/pass/access-token"]]
      [:juxt.pass.alpha/subject [:re "https://site.test/subjects/(.+)"]]
      [:juxt.pass.alpha/application [:re "https://site.test/applications/(.+)"]]
      [:juxt.pass.alpha/scope {:optional true} :string]]]
    :juxt.pass.alpha/process
    [
     [:juxt.pass.alpha.process/update-in [0]
      'merge {:juxt.site.alpha/type "https://meta.juxt.site/pass/access-token"}]
     [:juxt.pass.alpha.malli/validate]
     [:xtdb.api/put]
     ]
    :juxt.pass.alpha/rules
    '[[(allowed? permission subject action resource)
       [id :juxt.pass.alpha/user user]
       [subject :juxt.pass.alpha/user-identity id]
       [permission :role role]
       [user :role role]]]})
  ;; end::create-action-issue-access-token![]
  )

(defn grant-permission-to-invoke-action-issue-access-token! []
  ;; tag::grant-permission-to-invoke-action-issue-access-token![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/users/issue-access-token"
    :role "User" ; <1>
    :juxt.pass.alpha/action "https://site.test/actions/issue-access-token"
    :juxt.pass.alpha/purpose nil})
  ;; end::grant-permission-to-invoke-action-issue-access-token![]
  )

;; Authorization Server

(defn install-authorization-server! []
  ;; tag::install-authorization-server![]
  (put!
   {:xt/id "https://auth.site.test/oauth/authorize"
    :juxt.site.alpha/methods
    {:get
     {:juxt.site.alpha/handler 'juxt.pass.alpha.authorization-server/authorize
      :juxt.pass.alpha/actions #{"https://site.test/actions/authorize-application"}

      ;; Should we create a 'session space' which functions like a protection
      ;; space?  Like a protection space, it will extract the ::pass/subject
      ;; from the session and place into the request - see
      ;; juxt.pass.alpha.session/wrap-associate-session

      :juxt.pass.alpha/session-cookie "id"
      ;; This will be called with query parameter return-to set to ::site/uri
      ;; (effective URI) of request
      :juxt.pass.alpha/redirect-when-no-session-session "https://site.test/_site/openid/auth0/login"
      }}})
  ;; end::install-authorization-server![]
  )

;; TODO: Put Authorization Server in a protection space

;; First Application

(defn invoke-put-application! []
  ;; tag::invoke-put-application![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/put-application"
   (make-application-doc
    :prefix "https://site.test/applications/"
    :client-id "local-terminal"
    :client-secret (as-hex-str (random-bytes 20))))
  ;; end::invoke-put-application![]
  )

(defn invoke-authorize-application! []
  ;; tag::invoke-authorize-application![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/authorize-application"
   (make-application-authorization-doc
    :prefix "https://site.test/authorizations/"
    :user "https://site.test/users/alice"
    :application "https://site.test/applications/local-terminal"))
  ;; end::invoke-authorize-application![]
  )

;; Deprecated: This overlaps with an existing subject
(defn create-test-subject! []
  ;; tag::create-test-subject![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/put-subject"
   {:xt/id "https://site.test/subjects/test"
    :juxt.pass.alpha/user-identity "https://site.test/user-identities/alice"}
   )
  ;; end::create-test-subject![]
  )

(defn invoke-issue-access-token! []
  ;; tag::invoke-issue-access-token![]
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/issue-access-token"
   (make-access-token-doc
    :token "test-access-token"
    :prefix "https://site.test/access-tokens/"
    :subject "https://site.test/subjects/test"
    :application "https://site.test/applications/local-terminal"
    :scope "read:admin"
    :expires-in-seconds (* 5 60)))
  ;; end::invoke-issue-access-token![]
  )

;; Other stuff

(defn create-action-put-error-resource! []
  (eval
   (substitute-actual-base-uri
    (quote
     ;; tag::create-action-put-error-resource![]
     (do-action
      "https://site.test/subjects/repl-default"
      "https://site.test/actions/create-action"
      {:xt/id "https://site.test/actions/put-error-resource"
       :juxt.pass.alpha/scope "write:resource"

       :juxt.pass.alpha.malli/args-schema
       [:tuple
        [:map
         [:xt/id [:re "https://site.test/_site/errors/[a-z\\-]{3,}"]]
         [:juxt.site.alpha/type [:= "ErrorResource"]]
         [:ring.response/status :int]]]

       :juxt.pass.alpha/process
       [
        [:juxt.pass.alpha.malli/validate]
        [:xtdb.api/put]]

       :juxt.pass.alpha/rules
       '[
         [(allowed? permission subject action resource)
          [permission :juxt.pass.alpha/user-identity i]
          [subject :juxt.pass.alpha/user-identity i]]]})
     ;; end::create-action-put-error-resource![]
     ))))

(defn grant-permission-to-put-error-resource! []
  (eval
   (substitute-actual-base-uri
    (quote
     ;; tag::grant-permission-to-put-error-resource![]
     (do-action
      "https://site.test/subjects/repl-default"
      "https://site.test/actions/grant-permission"
      {:xt/id "https://site.test/permissions/alice/put-error-resource"
       :juxt.pass.alpha/user "https://site.test/users/alice"
       :juxt.pass.alpha/action #{"https://site.test/actions/put-error-resource"}
       :juxt.pass.alpha/purpose nil})
     ;; end::grant-permission-to-put-error-resource![]
     ))))

(defn put-unauthorized-error-resource! []
  (eval
   (substitute-actual-base-uri
    (quote
     ;; tag::put-unauthorized-error-resource![]
     (do-action
      "https://site.test/subjects/repl-default"
      "https://site.test/actions/put-error-resource"
      {:xt/id "https://site.test/_site/errors/unauthorized"
       :juxt.site.alpha/type "ErrorResource"
       :ring.response/status 401})
     ;; end::put-unauthorized-error-resource![]
     ))))

(defn put-unauthorized-error-representation-for-html! []
  (eval
   (substitute-actual-base-uri
    (quote
     ;; tag::put-unauthorized-error-representation-for-html![]
     (do-action
      "https://site.test/subjects/repl-default"
      "https://site.test/actions/put-immutable-public-resource"
      {:xt/id "https://site.test/_site/errors/unauthorized.html"
       :juxt.site.alpha/variant-of "https://site.test/_site/errors/unauthorized"
       :juxt.http.alpha/content-type "text/html;charset=utf-8"
       :juxt.http.alpha/content "<h1>Unauthorized</h1>\r\n"})
     ;; end::put-unauthorized-error-representation-for-html![]
     ))))

(defn put-unauthorized-error-representation-for-html-with-login-link! []
  (eval
   (substitute-actual-base-uri
    (quote
     ;; tag::put-unauthorized-error-representation-for-html-with-login-link![]
     (do-action
      "https://site.test/subjects/repl-default"
      "https://site.test/actions/put-immutable-public-resource"
      {:xt/id "https://site.test/_site/errors/unauthorized.html"
       :juxt.site.alpha/variant-of "https://site.test/_site/errors/unauthorized"
       :juxt.http.alpha/content-type "text/html;charset=utf-8"
       :juxt.http.alpha/content (slurp "dev/unauthorized.html")})
     ;; end::put-unauthorized-error-representation-for-html-with-login-link![]
     ))))

(defn install-not-found []
  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/create-action"
   {:xt/id "https://site.test/actions/get-not-found"
    :juxt.pass.alpha/scope "read:resource"
    :juxt.pass.alpha/rules
    [
     ['(allowed? permission subject action resource)
      ['permission :xt/id]]]})

  (do-action
   "https://site.test/subjects/repl-default"
   "https://site.test/actions/grant-permission"
   {:xt/id "https://site.test/permissions/get-not-found"
    :juxt.pass.alpha/action "https://site.test/actions/get-not-found"
    :juxt.pass.alpha/purpose nil})

  (put!
   {:xt/id "urn:site:resources:not-found"
    :juxt.site.alpha/methods
    {:get {:juxt.pass.alpha/actions #{"https://site.test/actions/get-not-found"}}
     :head {:juxt.pass.alpha/actions #{"https://site.test/actions/get-not-found"}}}}))

;; Complete all tasks thus far directed by the book
(defn preliminaries! []
  (put-user!)
  (put-user-identity!)
  (put-subject!)
  (install-create-action!)
  (book-install-do-action-fn!)
  (permit-create-action!)
  (create-grant-permission-action!)
  (permit-grant-permission-action!)
  (create-action-put-user!)
  (grant-permission-to-invoke-action-put-user!)
  (create-action-put-user-identity!)
  (grant-permission-to-invoke-action-put-user-identity!)
  (create-action-put-subject!)
  (grant-permission-to-invoke-action-put-subject!)
  ;; This tackles the '404' problem.
  (install-not-found))

(defn setup-hello-world! []
  (create-action-put-immutable-public-resource!)
  (grant-permission-to-invoke-action-put-immutable-public-resource!)
  (create-action-get-public-resource!)
  (grant-permission-to-invoke-get-public-resource!)
  (create-hello-world-resource!)
  )

(defn protected-resource-preliminaries! []
  (create-action-put-immutable-protected-resource!)
  (grant-permission-to-put-immutable-protected-resource!)
  (create-action-get-protected-resource!))

(defn protection-spaces-preliminaries! []
  (create-action-put-protection-space!)
  (grant-permission-to-put-protection-space!))

(defn session-scopes-preliminaries! []
  (create-action-put-session-scope!)
  (grant-permission-to-put-session-scope!))

(defn applications-preliminaries! []
  (create-action-put-application!)
  (grant-permission-to-invoke-action-put-application!)
  (create-action-authorize-application!)
  (grant-permission-to-invoke-action-authorize-application!)
  (create-action-issue-access-token!)
  (grant-permission-to-invoke-action-issue-access-token!))

(defn setup-application! []
  (invoke-put-application!)
  (invoke-authorize-application!)
  (create-test-subject!)
  (invoke-issue-access-token!))

(defn init-all! []
  (preliminaries!)
  (setup-hello-world!)

  (protected-resource-preliminaries!)

  (protection-spaces-preliminaries!)

  (create-resource-protected-by-basic-auth!)
  (grant-permission-to-resource-protected-by-basic-auth!)
  (put-basic-protection-space!)
  (put-basic-auth-user-identity!)

  (session-scopes-preliminaries!)

  (create-resource-protected-by-session-scope!)
  (grant-permission-to-resource-protected-by-session-scope!)
  (create-session-scope!)
  (create-login-form!)
  (create-login-resource!)
  (create-action-login!)
  (grant-permission-to-invoke-action-login!)

  (applications-preliminaries!)
  (setup-application!)

  (create-resource-protected-by-bearer-auth!)
  (grant-permission-to-resource-protected-by-bearer-auth!)
  (put-bearer-protection-space!))
