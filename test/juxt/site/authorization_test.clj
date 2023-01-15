;; Copyright © 2022, JUXT LTD.

(ns juxt.site.authorization-test
  (:require
   [clojure.set :as set]
   [clojure.test :refer [deftest is are use-fixtures testing] :as t]
   [juxt.site.actions :as authz]
   [juxt.test.util :refer [xt-fixture submit-and-await! *xt-node* with-fixtures]]
   [xtdb.api :as xt]))

(use-fixtures :each xt-fixture)

;; First, we define the actors.

;; Site is a 'Bring Your Own Domain' thing, and it's common for domains to
;; define users in terms of the attributes of those users that are important to
;; the domain. So in this example we define our users without any keywords that
;; would be recognisable to site.

;; Note: if you're unfamiliar with the Alice and Bob characters, see
;; https://en.wikipedia.org/wiki/Alice_and_Bob#Cast_of_characters

(def ALICE
  {:xt/id "https://example.org/people/alice"
   ::type "Person"
   ::username "alice"})

(def BOB
  {:xt/id "https://example.org/people/bob"
   ::type "Person"
   ::username "bob"})

(def CARLOS
  {:xt/id "https://example.org/people/carlos"
   ::type "Person"
   ::username "carlos"})

(def FAYTHE
  {:xt/id "https://example.org/people/faythe"
   ::type "Person"
   ::username "faythe"})

(def OSCAR
  {:xt/id "https://example.org/people/oscar"
   ::type "Person"
   ::username "oscar"})

;; Applications. Applications access APIs on behalf of subjects. In most cases,
;; you can't access an API, certainly not a private one, without an application.

#_(def USER_APP
  {:xt/id "https://example.org/_site/apps/user"
   ::name "User App"
   :juxt.site/client-id "100"
   :juxt.site/client-secret "SecretUmbrella"
   :juxt.site/scope #{"read:resource" "write:resource"
                  "read:user"
                  "read:messages"
                  "read:health"}})

;; Subjects incorporate information about the person and other details. For
;; example, the device they are using, the method of authentication (whether
;; using 2FA), the level their claims can be trusted. Subjects are established
;; and stored in the user's session.

(def ALICE_SUBJECT
  {:xt/id "https://example.org/subjects/alice"
   ::person (:xt/id ALICE)
   ::email-verified true})

(def ALICE_UNVERIFIED_SUBJECT
  {:xt/id "https://example.org/subjects/unverified-alice"
   ::person (:xt/id ALICE)
   ::email-verified false})

(def BOB_SUBJECT
  {:xt/id "https://example.org/subjects/bob"
   ::person (:xt/id BOB)})

(def CARLOS_SUBJECT
  {:xt/id "https://example.org/subjects/carlos"
   ::person (:xt/id CARLOS)})

(def FAYTHE_SUBJECT
  {:xt/id "https://example.org/subjects/faythe"
   ::person (:xt/id FAYTHE)})

(def OSCAR_SUBJECT
  {:xt/id "https://example.org/subjects/oscar"
   ::person (:xt/id OSCAR)})

;; All access is via an access token. Access tokens reference the application
;; being used and the subject that the application is acting on behalf
;; of. Access tokens are Site documents and must contain, at the minimum,
;; :juxt.site/type, :juxt.site/subject and :juxt.site/application-client. An access token
;; might not be granted all the scopes that the application requests. When the
;; access token's scopes are limited with respect to the application's allowed
;; scopes, a :site/scope entry is added. This might be added at the creation of
;; the access token, or during its lifecycle (if the person represented by the
;; subject wishes to adjust the scope of the access token).

;; TODO: INTERNAL classification, different security models, see
;; https://en.m.wikipedia.org/wiki/Bell%E2%80%93LaPadula_model
;; PUBLIC

(def EMPLOYEE_LIST
  {:xt/id "https://example.org/employees/"
   :juxt.site/classification "INTERNAL"})

(def LOGIN_PAGE
  {:xt/id "https://example.org/login"
   :juxt.site/classification "PUBLIC"})

;; Neither Alice nor Carlos can see this resource because it doesn't have an
;; explicit classification.
(def UNCLASSIFIED_PAGE
  {:xt/id "https://example.org/sales-report.csv"})

;; Alice is an employee
;; Carlos isn't an employee, but can access the login page

;; This action might come as part of a 'web-server' capability 'pack' where Site
;; would 'know' that all GET requests to a resource would involve this specific
;; action.
(def GET_RESOURCE_ACTION
  {:xt/id "https://example.org/_site/actions/get-resource"
   :juxt.site/type "https://meta.juxt.site/types/action"
   :juxt.site/scope "read:resource"
   :juxt.site/rules
   '[
     ;; Unidentified visitors can read of PUBLIC resources
     [(allowed? subject action resource permission)
      [resource :juxt.site/classification "PUBLIC"]
      [(nil? subject)]
      ]

     ;; Identified visitors can also read PUBLIC resource
     [(allowed? subject action resource permission)
      [resource :juxt.site/classification "PUBLIC"]
      [subject :xt/id]]

     ;; Only persons granted permission to read INTERNAL resources
     [(allowed? subject action resource permission)
      [resource :juxt.site/classification "INTERNAL"]
      [permission ::person person]
      [subject ::person person]]
]})

(def ANYONE_CAN_READ_PUBLIC_RESOURCES
  {:xt/id "https://example.org/permissions/anyone-can-read-public-resources"
   :juxt.site/type "https://meta.juxt.site/types/permission"
   :juxt.site/action (:xt/id GET_RESOURCE_ACTION)
   :juxt.site/purpose nil})

(def ALICE_CAN_READ_INTERNAL_RESOURCES
  {:xt/id "https://example.org/permissions/alice-can-read-internal-resources"
   :juxt.site/type "https://meta.juxt.site/types/permission"
   :juxt.site/action (:xt/id GET_RESOURCE_ACTION)
   :juxt.site/purpose nil
   ::person (:xt/id ALICE)})

#_((t/join-fixtures [xt-fixture])
 (fn []
   ))

(deftest classified-resource-test
  (submit-and-await!
   [
    ;; Actors
    [::xt/put ALICE]
    [::xt/put CARLOS]

    ;; Actions
    [::xt/put GET_RESOURCE_ACTION]

    ;; Subjects
    [::xt/put ALICE_SUBJECT]
    [::xt/put CARLOS_SUBJECT]

    ;; Resources
    [::xt/put LOGIN_PAGE]
    [::xt/put EMPLOYEE_LIST]

    ;; Permissions
    [::xt/put ANYONE_CAN_READ_PUBLIC_RESOURCES]
    [::xt/put ALICE_CAN_READ_INTERNAL_RESOURCES]])

  (let [db (xt/db *xt-node*)]
    (are [subject resource expected]
        (let [permissions
              (authz/check-permissions
               db
               #{(:xt/id GET_RESOURCE_ACTION)}
               (cond-> {}
                 subject (assoc :juxt.site/subject subject)
                 resource (assoc :juxt.site/resource resource)))]
          (if expected
            (is (seq permissions))
            (is (not (seq permissions)))))

        ALICE_SUBJECT LOGIN_PAGE true
        ALICE_SUBJECT EMPLOYEE_LIST true

        CARLOS_SUBJECT LOGIN_PAGE true
        CARLOS_SUBJECT EMPLOYEE_LIST false

        nil LOGIN_PAGE true
        nil EMPLOYEE_LIST false)))

;; User directories

;; A long time ago, web servers supported 'user directories'. If you had an
;; account on a host and your username was 'alice', you could put files into a
;; public_html directory in your home directory and this would be published over
;; the WWW under http://host/~alice/. The tilde (~) indicates that the files
;; belong to the account owner. See
;; https://httpd.apache.org/docs/2.4/howto/public_html.html for further details.

;; We'll create a similar system here, using subjects/actions/resources.

;; TODO: Not a great first example! Try something easier to start with.

(def ALICE_USER_DIR_PRIVATE_FILE
  {:xt/id "https://example.org/~alice/private.txt"})

(def ALICE_USER_DIR_SHARED_FILE
  {:xt/id "https://example.org/~alice/shared.txt"})

(def READ_USER_DIR_ACTION
  {:xt/id "https://example.org/actions/read-user-dir"
   :juxt.site/type "https://meta.juxt.site/types/action"
   :juxt.site/scope "read:resource"
   :juxt.site/rules
   '[[(allowed? subject action resource permission)
      [permission ::person person]
      [subject ::person person]
      [person ::type "Person"]
      [resource :xt/id]
      [person ::username username]
      [(re-pattern "https://example.org/~([a-z]+)/.+") resource-pattern]
      [(re-matches resource-pattern resource) [_ user]]
      [(= user username)]]]})

(def WRITE_USER_DIR_ACTION
  {:xt/id "https://example.org/actions/write-user-dir"
   :juxt.site/type "https://meta.juxt.site/types/action"
   :juxt.site/scope "write:resource"
   :juxt.site/rules
   '[[(allowed? subject action resource permission)
      [permission ::person person]
      [subject ::person person]
      [person ::type "Person"]
      [person ::username username]
      [(re-pattern "https://example.org/~([a-z]+)/.+") resource-pattern]
      [(re-matches resource-pattern resource) [_ user]]
      [(= user username)]]]})

(def READ_SHARED_ACTION
  {:xt/id "https://example.org/actions/read-shared"
   :juxt.site/type "https://meta.juxt.site/types/action"
   :juxt.site/scope "read:resource"
   :juxt.site/rules
   '[[(allowed? subject action resource permission)
      [permission ::person person]
      [person ::type "Person"]
      [subject ::person person]
      [resource :xt/id]
      [permission :juxt.site/resource resource]]]})

(def ALICE_CAN_READ
  {:xt/id "https://example.org/permissions/alice-can-read"
   :juxt.site/type "https://meta.juxt.site/types/permission"
   ::person "https://example.org/people/alice"
   :juxt.site/action #{"https://example.org/actions/read-shared"
                   "https://example.org/actions/read-user-dir"}
   :juxt.site/purpose nil})

(def ALICE_CAN_WRITE_USER_DIR_CONTENT
  {:xt/id "https://example.org/permissions/alice-can-write-user-dir-content"
   :juxt.site/type "https://meta.juxt.site/types/permission"
   ::person "https://example.org/people/alice"
   :juxt.site/action "https://example.org/actions/write-user-dir"
   :juxt.site/purpose nil})

(def BOB_CAN_READ
  {:xt/id "https://example.org/permissions/bob-can-read"
   :juxt.site/type "https://meta.juxt.site/types/permission"
   ::person "https://example.org/people/bob"
   :juxt.site/action #{"https://example.org/actions/read-shared"
                   "https://example.org/actions/read-user-dir"}
   :juxt.site/purpose nil})

(def ALICES_SHARES_FILE_WITH_BOB
  {:xt/id "https://example.org/permissions/alice-shares-file-with-bob"
   :juxt.site/type "https://meta.juxt.site/types/permission"
   ::person "https://example.org/people/bob"
   :juxt.site/action "https://example.org/actions/read-shared"
   :juxt.site/purpose nil
   :juxt.site/resource "https://example.org/~alice/shared.txt"})

(def BOB_CAN_WRITE_USER_DIR_CONTENT
  {:xt/id "https://example.org/permissions/bob-can-write-user-dir-content"
   :juxt.site/type "https://meta.juxt.site/types/permission"
   ::person "https://example.org/people/bob"
   :juxt.site/action "https://example.org/actions/write-user-dir"
   :juxt.site/purpose nil})

;; Scopes. Actions inhabit scopes.

(defn effective-scope [db access-token]
  (let [access-token-doc (xt/entity db access-token)
        _ (assert access-token-doc)
        app (xt/entity db (:juxt.site/application-client access-token-doc))
        _ (assert app)
        scope (:juxt.site/scope app)
        _ (assert scope)
        access-token-scope (:juxt.site/scope access-token-doc)]
    (cond-> scope
      access-token-scope (set/intersection access-token-scope))))

(deftest user-dir-test
  (submit-and-await!
   [
    ;; Actors
    [::xt/put ALICE]
    [::xt/put BOB]
    [::xt/put CARLOS]

    ;; Subjects
    [::xt/put ALICE_SUBJECT]
    [::xt/put BOB_SUBJECT]
    [::xt/put CARLOS_SUBJECT]

    ;; Actions
    [::xt/put READ_USER_DIR_ACTION]
    [::xt/put READ_SHARED_ACTION]
    [::xt/put WRITE_USER_DIR_ACTION]

    ;; Resources
    [::xt/put ALICE_USER_DIR_PRIVATE_FILE]
    [::xt/put ALICE_USER_DIR_SHARED_FILE]

    ;; Permissions
    [::xt/put ALICE_CAN_READ]
    [::xt/put ALICE_CAN_WRITE_USER_DIR_CONTENT]
    [::xt/put BOB_CAN_READ]
    [::xt/put BOB_CAN_WRITE_USER_DIR_CONTENT]
    [::xt/put ALICES_SHARES_FILE_WITH_BOB]])

  (let [db (xt/db *xt-node*)]
    (are [subject action resource ok?]
        (let [actual (authz/check-permissions
                      db
                      #{(:xt/id action)}
                      {:juxt.site/subject subject
                       :juxt.site/resource resource})]
          (if ok? (is (seq actual)) (is (not (seq actual)))))

      ;; Alice can read her own private file.
      ALICE_SUBJECT
      READ_USER_DIR_ACTION
      ALICE_USER_DIR_PRIVATE_FILE
      true

      ;; Alice can read the file in her user directory which she has shared with
      ;; Bob.
      ALICE_SUBJECT
      READ_USER_DIR_ACTION
      ALICE_USER_DIR_SHARED_FILE
      true

      ;; Bob cannot read Alice's private file.
      BOB_SUBJECT
      READ_USER_DIR_ACTION
      ALICE_USER_DIR_PRIVATE_FILE
      false

      ;; Bob can read the file Alice has shared with him.
      BOB_SUBJECT
      READ_SHARED_ACTION
      ALICE_USER_DIR_SHARED_FILE
      true

      ;; Alice can put a file to her user directory
      ALICE_SUBJECT
      WRITE_USER_DIR_ACTION
      {:xt/id "https://example.org/~alice/foo.txt"}
      true

      ;; Alice can't put a file to her user directory without write:resource
      ;; scope

      ;; TODO: Scope checks can and should be done independently of checking permissions
                                        ;      "https://example.org/tokens/alice-readonly"
                                        ;      #{"https://example.org/actions/write-user-dir"}
                                        ;      "https://example.org/~alice/foo.txt"
                                        ;      false

      ;; Alice can't put a file to Bob's user directory
      ALICE_SUBJECT
      WRITE_USER_DIR_ACTION
      {:xt/id "https://example.org/~bob/foo.txt"}
      false

      ;; Alice can't put a file outside her user directory
      ALICE_SUBJECT
      WRITE_USER_DIR_ACTION
      {:xt/id "https://example.org/index.html"}
      false

      ;; Bob can put a file to his user directory
      BOB_SUBJECT
      WRITE_USER_DIR_ACTION
      {:xt/id "https://example.org/~bob/foo.txt"}
      true

      ;; Bob can't put a file to Alice's directory
      BOB_SUBJECT
      WRITE_USER_DIR_ACTION
      {:xt/id "https://example.org/~alice/foo.txt"}
      false

      ;; Carlos cannot put a file to his user directory, as he hasn't been
      ;; granted the write-user-dir action.
      CARLOS_SUBJECT
      WRITE_USER_DIR_ACTION
      {:xt/id "https://example.org/~carlos/foo.txt"}
      false
      )

    (are [subject actions expected]
        (is (= expected
               (authz/allowed-resources
                db
                actions
                {:juxt.site/subject subject})))

      ;; Alice can see all her files.
      ALICE_SUBJECT
      #{"https://example.org/actions/read-user-dir"
        "https://example.org/actions/read-shared"}
      #{["https://example.org/~alice/shared.txt"]
        ["https://example.org/~alice/private.txt"]}

      ;; Bob can only see the file Alice has shared with him.
      BOB_SUBJECT
      #{"https://example.org/actions/read-user-dir"
        "https://example.org/actions/read-shared"}
      #{["https://example.org/~alice/shared.txt"]}

      ;; Carlos sees nothing
      CARLOS_SUBJECT
      #{"https://example.org/actions/read-user-dir"
        "https://example.org/actions/read-shared"}
      #{})

    ;; Given a resource and a set of actions, which subjects can access
    ;; and via which actions?

    #_(are [resource actions expected]
          (is (= expected (authz/allowed-subjects
                           db
                           resource actions
                           {})))

      "https://example.org/~alice/shared.txt"
        #{"https://example.org/actions/read-user-dir"
          "https://example.org/actions/read-shared"}
        #{{:subject "https://example.org/subjects/bob",
           :action "https://example.org/actions/read-shared"}
          {:subject "https://example.org/subjects/alice",
           :action "https://example.org/actions/read-user-dir"}}

        "https://example.org/~alice/private.txt"
        #{"https://example.org/actions/read-user-dir"
          "https://example.org/actions/read-shared"}
        #{{:subject "https://example.org/subjects/alice",
           :action "https://example.org/actions/read-user-dir"}}

        ;; Cannot see anything without a scope
        #_"https://example.org/~alice/shared.txt"
        #_#{"https://example.org/actions/read-user-dir"
          "https://example.org/actions/read-shared"}
        #_#{})))

(deftest constrained-pull-test
  (let [READ_USERNAME_ACTION
        {:xt/id "https://example.org/actions/read-username"
         :juxt.site/type "https://meta.juxt.site/types/action"
         :juxt.site/scope "read:user"
         :juxt.site/pull [::username]
         :juxt.site/rules
         '[[(allowed? subject action resource permission)
            [permission ::person person]
            [subject ::person person]
            [person ::type "Person"]
            [permission :juxt.site/resource resource]]]}

        READ_SECRETS_ACTION
        {:xt/id "https://example.org/actions/read-secrets"
         :juxt.site/type "https://meta.juxt.site/types/action"
         :juxt.site/scope "read:user"
         :juxt.site/pull [::secret]
         :juxt.site/rules
         '[[(allowed? subject action resource permission)
            [permission ::person person]
            [subject ::person person]
            [person ::type "Person"]
            [permission :juxt.site/resource resource]]]}

        BOB_CAN_READ_ALICE_USERNAME
        {:xt/id "https://example.org/permissions/bob-can-read-alice-username"
         :juxt.site/type "https://meta.juxt.site/types/permission"
         ::person "https://example.org/people/bob"
         :juxt.site/action "https://example.org/actions/read-username"
         :juxt.site/purpose nil
         :juxt.site/resource "https://example.org/people/alice"}

        BOB_CAN_READ_ALICE_SECRETS
        {:xt/id "https://example.org/permissions/bob-can-read-alice-secrets"
         :juxt.site/type "https://meta.juxt.site/types/permission"
         ::person "https://example.org/people/bob"
         :juxt.site/action "https://example.org/actions/read-secrets"
         :juxt.site/purpose nil
         :juxt.site/resource "https://example.org/people/alice"}

        CARLOS_CAN_READ_ALICE_USERNAME
        {:xt/id "https://example.org/permissions/carlos-can-read-alice-username"
         :juxt.site/type "https://meta.juxt.site/types/permission"
         ::person "https://example.org/people/carlos"
         :juxt.site/action "https://example.org/actions/read-username"
         :juxt.site/purpose nil
         :juxt.site/resource "https://example.org/people/alice"}]

    (submit-and-await!
     [
      ;; Actors
      [::xt/put (assoc ALICE ::secret "foo")]
      [::xt/put BOB]
      [::xt/put CARLOS]

      ;; Subjects
      [::xt/put ALICE_SUBJECT]
      [::xt/put BOB_SUBJECT]
      [::xt/put CARLOS_SUBJECT]

      ;; Actions
      [::xt/put READ_USERNAME_ACTION]
      [::xt/put READ_SECRETS_ACTION]

      ;; Permissions
      [::xt/put BOB_CAN_READ_ALICE_USERNAME]
      [::xt/put BOB_CAN_READ_ALICE_SECRETS]
      [::xt/put CARLOS_CAN_READ_ALICE_USERNAME]
      ])

    ;; Bob can read Alice's secret
    (let [db (xt/db *xt-node*)]
      (are [subject expected]
          (let [actual
                (authz/pull-allowed-resource
                 db
                 #{(:xt/id READ_USERNAME_ACTION) (:xt/id READ_SECRETS_ACTION)}
                 ALICE
                 {:juxt.site/subject subject})]
            (is (= expected actual)))

        BOB_SUBJECT {::username "alice" ::secret "foo"}
        CARLOS_SUBJECT {::username "alice"}))))

;; In this test, Alice and Bob communicate with each other and can see the
;; content of each other's messages.  Faythe is tasked with monitoring
;; conversations between Alice and Bob, so can read the metadata (when a message
;; was sent, between which participants, etc.) but for privacy reasons cannot
;; see the content of each message.
;;
;; This test tests the :juxt.site/pull feature which restricts what documents
;; attributes can be read.
;;
;; Permissions are modelled here as 'group membership', meaning that if a
;; participant is a member of a group, that alone gives them permission to the
;; actions. However, this feels wrong: it is not intuitively obvious what is
;; going on.
;;
;; TODO (@cwi): Revisit, remove the idea of groups in this test and have an
;; explicit permission. Perhaps the demonstration/testing of 'complex'
;; permissions which apply only to a certain group should be extracted into a
;; different test.
;;
;; TODO: Let's rename person to user to align with the book
(deftest pull-allowed-resources-test
  (let [READ_MESSAGE_CONTENT_ACTION
        {:xt/id "https://example.org/actions/read-message-content"
         :juxt.site/type "https://meta.juxt.site/types/action"
         :juxt.site/scope "read:messages"
         :juxt.site/pull [::content]
         :juxt.site/rules
         '[[(allowed? subject action resource permission)
            [permission ::person person]
            [subject ::person person]
            [person ::type "Person"]
            [permission ::group group]
            [resource ::group group]
            [resource :juxt.site/type "Message"]]]}

        READ_MESSAGE_METADATA_ACTION
        {:xt/id "https://example.org/actions/read-message-metadata"
         :juxt.site/type "https://meta.juxt.site/types/action"
         :juxt.site/scope "read:messages"
         :juxt.site/pull [::from ::to ::date]
         :juxt.site/rules
         '[[(allowed? subject action resource permission)
            [permission ::person person]
            [subject ::person person]
            [person ::type "Person"]
            [permission ::group group]
            [resource ::group group]
            [resource :juxt.site/type "Message"]]]}

        ALICE_BELONGS_GROUP_A
        {:xt/id "https://example.org/group/a/alice"
         :juxt.site/type "https://meta.juxt.site/types/permission"
         ::person (:xt/id ALICE)
         ::group :a
         :juxt.site/action #{(:xt/id READ_MESSAGE_CONTENT_ACTION)
                         (:xt/id READ_MESSAGE_METADATA_ACTION)}
         :juxt.site/purpose nil}

        BOB_BELONGS_GROUP_A
        {:xt/id "https://example.org/group/a/bob"
         :juxt.site/type "https://meta.juxt.site/types/permission"
         ::person (:xt/id BOB)
         ::group :a
         :juxt.site/action #{(:xt/id READ_MESSAGE_CONTENT_ACTION)
                         (:xt/id READ_MESSAGE_METADATA_ACTION)}
         :juxt.site/purpose nil}

        ;; Faythe is a trusted admin of Group A. She can see the metadata but
        ;; not the content of messages.
        FAYTHE_MONITORS_GROUP_A
        {:xt/id "https://example.org/group/a/faythe"
         :juxt.site/type "https://meta.juxt.site/types/permission"
         ::person (:xt/id FAYTHE)
         ::group :a
         :juxt.site/action #{(:xt/id READ_MESSAGE_METADATA_ACTION)}
         :juxt.site/purpose nil}]

    (submit-and-await!
     [
      ;; Actions
      [::xt/put READ_MESSAGE_CONTENT_ACTION]
      [::xt/put READ_MESSAGE_METADATA_ACTION]

      ;; Actors
      [::xt/put ALICE]
      [::xt/put BOB]
      [::xt/put CARLOS]
      [::xt/put FAYTHE]

      ;; Subjects
      [::xt/put ALICE_SUBJECT]
      [::xt/put BOB_SUBJECT]
      [::xt/put CARLOS_SUBJECT]
      [::xt/put FAYTHE_SUBJECT]

      ;; Permissions
      [::xt/put ALICE_BELONGS_GROUP_A]
      [::xt/put BOB_BELONGS_GROUP_A]
      [::xt/put FAYTHE_MONITORS_GROUP_A]

      ;; Messages
      [::xt/put
       {:xt/id "https://example.org/messages/1"
        :juxt.site/type "Message"
        ::group :a
        ::from (:xt/id ALICE)
        ::to (:xt/id BOB)
        ::date "2022-03-07T13:00:00"
        ::content "Hello Bob!"}]

      [::xt/put
       {:xt/id "https://example.org/messages/2"
        :juxt.site/type "Message"
        ::group :a
        ::from (:xt/id BOB)
        ::to (:xt/id ALICE)
        ::date "2022-03-07T13:00:10"
        ::content "Hi Alice, how are you?"}]

      [::xt/put
       {:xt/id "https://example.org/messages/3"
        :juxt.site/type "Message"
        ::group :a
        ::from (:xt/id ALICE)
        ::to (:xt/id BOB)
        ::date "2022-03-07T13:00:20"
        ::content "Great thanks. I've reset your siteword, btw."}]

      [::xt/put
       {:xt/id "https://example.org/messages/4"
        :juxt.site/type "Message"
        ::group :a
        ::from (:xt/id BOB)
        ::to (:xt/id ALICE)
        ::date "2022-03-07T13:00:40"
        ::content "Thanks, what is it?"}]

      [::xt/put
       {:xt/id "https://example.org/messages/5"
        :juxt.site/type "Message"
        ::group :a
        ::from (:xt/id ALICE)
        ::to (:xt/id BOB)
        ::date "2022-03-07T13:00:50"
        ::content "It's 'BananaTree@1230', you should definitely change it at some point."}]

      [::xt/put
       {:xt/id "https://example.org/messages/6"
        :juxt.site/type "Message"
        ::group :a
        ::from (:xt/id BOB)
        ::to (:xt/id ALICE)
        ::date "2022-03-07T13:00:50"
        ::content "Thanks Alice, that's very kind of you - see you at lunch!"}]])

    (let [get-messages
          (fn [subject]
            (let [db (xt/db *xt-node*)]
              (authz/pull-allowed-resources
               db
               #{(:xt/id READ_MESSAGE_CONTENT_ACTION)
                 (:xt/id READ_MESSAGE_METADATA_ACTION)}
               {:juxt.site/subject subject})))]

      ;; Alice and Bob can read all the messages in the group
      (let [messages (get-messages ALICE_SUBJECT)]
        (is (= 6 (count messages)))
        (is (= #{::from ::to ::date ::content} (set (keys (first messages))))))

      (let [messages (get-messages BOB_SUBJECT)]
        (is (= 6 (count messages)))
        (is (= #{::from ::to ::date ::content} (set (keys (first messages))))))

      ;; Carlos cannot see any of the messages
      (is (zero? (count (get-messages CARLOS_SUBJECT))))

      ;; Faythe can read meta-data of the conversation between Alice and Bob but
      ;; not the content of the messages.
      (let [messages (get-messages FAYTHE_SUBJECT)]
        (is (= 6 (count messages)))
        (is (= #{::from ::to ::date} (set (keys (first messages))))))

      ;; We can specify an :include-rules entry to pull-allowed-resources to
      ;; restrict the resources that are found to some additional
      ;; criteria. Currently this is as close as we get to providing full query
      ;; capabilities.
      (is (= 3 (count
                (let [db (xt/db *xt-node*)]
                  (authz/pull-allowed-resources
                   db
                   #{(:xt/id READ_MESSAGE_CONTENT_ACTION)
                     (:xt/id READ_MESSAGE_METADATA_ACTION)}
                   {:juxt.site/subject ALICE_SUBJECT
                    :juxt.site/include-rules [['(include? subject action message)
                                           ['message ::from (:xt/id ALICE)]]]}))))))))

;; Alice has a medical record. She wants to allow Oscar access to it, but only
;; in emergencies (to provide to a doctor in case of urgent need).

;; One way of achieving this is to segment actions by purpose.

(deftest purpose-with-distinct-actions-test
  (let [READ_MEDICAL_RECORD_ACTION
        {:xt/id "https://example.org/actions/read-medical-record"
         :juxt.site/type "https://meta.juxt.site/types/action"
         :juxt.site/scope "read:health"
         :juxt.site/pull ['*]
         :juxt.site/alert-log false
         :juxt.site/rules
         '[[(allowed? subject action resource permission)
            [permission ::person person]
            [subject ::person person]
            [person ::type "Person"]
            [resource :juxt.site/type "MedicalRecord"]]]}

        EMERGENCY_READ_MEDICAL_RECORD_ACTION
        {:xt/id "https://example.org/actions/emergency-read-medical-record"
         :juxt.site/type "https://meta.juxt.site/types/action"
         :juxt.site/scope "read:health"
         :juxt.site/pull ['*]
         :juxt.site/alert-log true
         :juxt.site/rules
         '[[(allowed? subject action resource permission)
            [permission ::person person]
            [subject ::person person]
            [person ::type "Person"]
            [resource :juxt.site/type "MedicalRecord"]]]}]

    (submit-and-await!
     [
      ;; Actors
      [::xt/put ALICE]
      [::xt/put OSCAR]

      ;; Subjects
      [::xt/put ALICE_SUBJECT]
      [::xt/put OSCAR_SUBJECT]

      ;; Actions
      [::xt/put READ_MEDICAL_RECORD_ACTION]
      [::xt/put EMERGENCY_READ_MEDICAL_RECORD_ACTION]

      ;; Permissions
      [::xt/put
       {:xt/id "https://example.org/alice/medical-record/grants/oscar"
        :juxt.site/type "https://meta.juxt.site/types/permission"
        ::person (:xt/id OSCAR)
        :juxt.site/action #{(:xt/id EMERGENCY_READ_MEDICAL_RECORD_ACTION)}
        :juxt.site/purpose nil}]

      ;; Resources
      [::xt/put
       {:xt/id "https://example.org/alice/medical-record"
        :juxt.site/type "MedicalRecord"
        ::content "Medical info"}]])

    (let [get-medical-records
          (fn [subject action]
            (let [db (xt/db *xt-node*)]
              (authz/pull-allowed-resources
               db
               #{(:xt/id action)}
               {:juxt.site/subject subject})))

          get-medical-record
          (fn [subject action]
            (let [db (xt/db *xt-node*)]
              (authz/pull-allowed-resource
               db
               #{(:xt/id action)}
               {:xt/id "https://example.org/alice/medical-record"}
               {:juxt.site/subject subject})))]

      (is (zero? (count (get-medical-records OSCAR_SUBJECT READ_MEDICAL_RECORD_ACTION))))
      (is (= 1 (count (get-medical-records OSCAR_SUBJECT EMERGENCY_READ_MEDICAL_RECORD_ACTION))))

      (is (not (get-medical-record OSCAR_SUBJECT READ_MEDICAL_RECORD_ACTION)))
      (is (get-medical-record OSCAR_SUBJECT EMERGENCY_READ_MEDICAL_RECORD_ACTION)))))

;; An alternative way of achieving the same result is to specify a purpose when
;; granting a permission.

(deftest purpose-test
  (let [READ_MEDICAL_RECORD_ACTION
        {:xt/id "https://example.org/actions/read-medical-record"
         :juxt.site/type "https://meta.juxt.site/types/action"
         :juxt.site/scope "read:health"
         :juxt.site/pull ['*]
         :juxt.site/rules
         '[[(allowed? subject action resource permission)
            [permission ::person person]
            [subject ::person person]
            [person ::type "Person"]
            [permission :juxt.site/purpose purpose]
            [resource :juxt.site/type "MedicalRecord"]]]}]

    (submit-and-await!
     [
      ;; Actors
      [::xt/put ALICE]
      [::xt/put OSCAR]

      ;; Subjects
      [::xt/put ALICE_SUBJECT]
      [::xt/put OSCAR_SUBJECT]

      ;; Actions
      [::xt/put READ_MEDICAL_RECORD_ACTION]

      ;; Purposes
      [::xt/put
       {:xt/id "https://example.org/purposes/emergency"
        :juxt.site/type "Purpose"
        ::description "Emergency access to vital personal information."
        ::gdpr-interest "VITAL"}]

      ;; Permissions
      [::xt/put
       {:xt/id "https://example.org/alice/medical-record/grants/oscar"
        :juxt.site/type "https://meta.juxt.site/types/permission"
        ::person (:xt/id OSCAR)
        :juxt.site/action (:xt/id READ_MEDICAL_RECORD_ACTION)
        :juxt.site/purpose "https://example.org/purposes/emergency"}]

      ;; Resources
      [::xt/put
       {:xt/id "https://example.org/alice/medical-record"
        :juxt.site/type "MedicalRecord"
        ::content "Medical info"}]])

    (let [get-medical-records
          (fn [subject action purpose]
            (let [db (xt/db *xt-node*)]
              (authz/pull-allowed-resources
               db
               #{(:xt/id action)}
               {:juxt.site/subject subject
                :juxt.site/purpose purpose})))

          get-medical-record
          (fn [subject action purpose]
            (let [db (xt/db *xt-node*)]
              (authz/pull-allowed-resource
               db
               #{(:xt/id action)}
               {:xt/id "https://example.org/alice/medical-record"}
               {:juxt.site/subject subject
                :juxt.site/purpose purpose})))]

      (is (zero? (count (get-medical-records OSCAR_SUBJECT READ_MEDICAL_RECORD_ACTION "https://example.org/purposes/marketing"))))
      (is (= 1 (count (get-medical-records OSCAR_SUBJECT READ_MEDICAL_RECORD_ACTION "https://example.org/purposes/emergency"))))

      (is (nil? (get-medical-record OSCAR_SUBJECT READ_MEDICAL_RECORD_ACTION "https://example.org/purposes/marketing")))
      (is (get-medical-record OSCAR_SUBJECT READ_MEDICAL_RECORD_ACTION "https://example.org/purposes/emergency")))))

;; Bootstrapping

;; TODO
;; Next up. Sharing itself. Is Alice even permitted to share her files?
;; read-only, read/write
;; Answer @jms's question: is it possible for Sue to grant a resource for
;; which she hasn't herself access?

(def SUE
  {:xt/id "https://example.org/people/sue"
   ::type "Person"
   ::username "sue"})

#_(def ADMIN_APP
  {:xt/id "https://example.org/_site/apps/admin"
   ::name "Admin App"
   :juxt.site/client-id "101"
   :juxt.site/client-secret "SecretArmadillo"
   :juxt.site/scope #{"read:admin" "write:admin"}})

(def SUE_SUBJECT
  {:xt/id "https://example.org/subjects/sue"
   ::person (:xt/id SUE)
   ::email-verified true})

(def CREATE_PERSON_ACTION
  {:xt/id "https://example.org/actions/create-person"
   :juxt.site/type "https://meta.juxt.site/types/action"
   :juxt.site/scope "write:admin"

   :juxt.site.malli/args-schema
   [:tuple
    [:map
     [::type [:= "Person"]]
     [::username [:string]]]]

   :juxt.site/process
   [
    [:juxt.site.process/update-in [0] 'merge {::type "Person"}]
    [:juxt.site.malli/validate]
    [::xt/put]]

   :juxt.site/rules
   '[[(allowed? subject action resource permission)
      [permission ::person person]
      [subject ::person person]
      [person ::type "Person"]]]})

#_(deftest do-action-test
  (submit-and-await!
   [
    ;; Actors
    [::xt/put SUE]
    [::xt/put CARLOS]

    ;; Subjects
    [::xt/put SUE_SUBJECT]
    [::xt/put CARLOS_SUBJECT]

    ;; Actions
    [::xt/put CREATE_PERSON_ACTION]
    #_[::xt/put CREATE_IDENTITY_ACTION]

    ;; Permissions
    [::xt/put
     {:xt/id "https://example.org/permissions/sue/create-person"
      :juxt.site/type "https://meta.juxt.site/types/permission"
      ::person (:xt/id SUE)
      :juxt.site/action (:xt/id CREATE_PERSON_ACTION)
      :juxt.site/purpose nil #_"https://example.org/purposes/bootsrapping-system"}]

    ;; Functions
    [::xt/put (authz/install-do-action-fn)]])

  ;; Sue creates the user Alice, with an identity
  (let [db (xt/db *xt-node*)]
    (is
     (seq
      (authz/check-permissions
       db
       #{(:xt/id CREATE_PERSON_ACTION)}
       {:juxt.site/subject (:xt/id SUE_SUBJECT)})))
    (is
     (not
      (seq
       (authz/check-permissions
        db
        #{}
        {:juxt.site/subject (:xt/id SUE_SUBJECT)}
        )))))

  (assert *xt-node*)

  (authz/do-action
   {:juxt.site/xt-node *xt-node*
    :juxt.site/subject (:xt/id SUE_SUBJECT)
    :juxt.site/action CREATE_PERSON_ACTION}
   ALICE)

  (is (xt/entity (xt/db *xt-node*) (:xt/id ALICE)))

  ;; This fails because we haven't provided the ::username

  (is (thrown? clojure.lang.ExceptionInfo
               (authz/do-action
                {:juxt.site/xt-node *xt-node*}
                {:juxt.site/subject (:xt/id ALICE_SUBJECT)}
                (:xt/id CREATE_PERSON_ACTION)
                BOB)))

  (is (not (xt/entity (xt/db *xt-node*) (:xt/id BOB))))

  ;; TODO: Test for validation errors
  )



;; TODO: Test actions like this:
;;(authz/process-args CREATE_PERSON_ACTION [{::username "alice"}])

;; Create a trading desk - equities swaps and corporate bonds
;; Traders can see their own trades, but not the trades others
;; Traders belong to a desk
;; Heads of desk can see all their desk's trades
;; People in the regulatory control have a role which allows them to see all trades
(deftest roles-as-permissions-test
  (submit-and-await!
   [
    ;; Desks
    [::xt/put {:xt/id "https://example.org/desks/equities/swaps"
               ::type "Desk"}]
    [::xt/put {:xt/id "https://example.org/desks/equities/bonds"
               ::type "Desk"}]

    ;; Actions

    ;; Add an action that lists trades, pulling all attributes
    [::xt/put {:xt/id "https://example.org/actions/list-trades"
               :juxt.site/type "https://meta.juxt.site/types/action"
               :juxt.site/pull '[*]
               :juxt.site/rules
               '[
                 ;; Allow traders to see their own trades
                 [(allowed? person action trade role-membership)
                  [role-membership ::role "https://example.org/roles/trader"]
                  [role-membership ::person person]
                  [trade ::type "Trade"]
                  [trade ::trader person]]

                 [(allowed? person action trade role-membership)
                  ;; Subjects who have the head-of-desk role
                  [role-membership ::role "https://example.org/roles/head-of-desk"]
                  [role-membership ::person person]
                  ;; trades
                  [trade ::type "Trade"]
                  ;; from the same desk
                  [trade ::desk desk]
                  [role-membership ::desk desk]]]}]

    ;; Add an action that lists trades, pulling only that trade attributes that
    ;; are accessible to a regulatory risk controller.
    [::xt/put {:xt/id "https://example.org/actions/control-list-trades"
               :juxt.site/type "https://meta.juxt.site/types/action"
               :juxt.site/pull '[::desk ::value :xt/id]
               :juxt.site/rules
               '[
                 [(allowed? person action trade role-membership)
                  [role-membership ::role "https://example.org/roles/regulatory-risk-controller"]
                  [role-membership ::person person]
                  [trade ::type "Trade"]
                  ]]}]

    [::xt/put {:xt/id "https://example.org/actions/get-trader-personal-info"
               :juxt.site/type "https://meta.juxt.site/types/action"
               :juxt.site/pull '[*]
               :juxt.site/rules
               '[
                 [(allowed? head-of-desk action trader role-membership)

                  ;; Subjects who have the head-of-desk role
                  [role-membership :juxt.site/type "https://meta.juxt.site/types/permission"]
                  [role-membership ::role "https://example.org/roles/head-of-desk"]
                  [role-membership ::person head-of-desk]

                  [trader ::type "Person"]
                  ;; Only for a trader that works on the same desk
                  [trader-role-membership :juxt.site/type "https://meta.juxt.site/types/permission"]
                  [trader-role-membership ::person trader]
                  [trader-role-membership ::desk desk]
                  [role-membership ::desk desk]]]}]

    ;; Actors

    ;; Sam
    [::xt/put {:xt/id "https://example.org/people/sam"
               ::type "Person"
               ::name "Sam Charlton"}]

    ;; Sam is a swaps trader. She has the 'permission' to assume the role of a
    ;; trader. This document authorizes her, according to the rules of
    ;; (potentially various) actions.
    [::xt/put {:xt/id "https://example.org/people/sam/position"
               :juxt.site/type "https://meta.juxt.site/types/permission"
               :juxt.site/action #{"https://example.org/actions/list-trades"}
               :juxt.site/purpose "Trading"
               ::person "https://example.org/people/sam"
               ::role #{"https://example.org/roles/trader"}
               ::desk "https://example.org/desks/equities/swaps"}]

    ;; Steve
    [::xt/put {:xt/id "https://example.org/people/steve"
               ::type "Person"
               ::name "Steven Greene"}]

    ;; Steve is also a swaps trader
    [::xt/put {:xt/id "https://example.org/people/steve/position"
               :juxt.site/type "https://meta.juxt.site/types/permission"
               :juxt.site/action #{"https://example.org/actions/list-trades"}
               :juxt.site/purpose "Trading"
               ::person "https://example.org/people/steve"
               ::role #{"https://example.org/roles/trader"}
               ::desk "https://example.org/desks/equities/swaps"}]

    ;; Susie
    [::xt/put {:xt/id "https://example.org/people/susie"
               ::type "Person"
               ::name "Susie Young"}]

    ;; Susie is the head of the swaps desk
    [::xt/put {:xt/id "https://example.org/people/susie/position"
               :juxt.site/type "https://meta.juxt.site/types/permission"
               :juxt.site/action #{"https://example.org/actions/list-trades" "https://example.org/actions/get-trader-personal-info"}
               :juxt.site/purpose #{"Trading" "LineManagement"}
               ::person "https://example.org/people/susie"
               ::role #{"https://example.org/roles/head-of-desk"}
               ::desk "https://example.org/desks/equities/swaps"}]

    ;; Brian is a bond trader
    [::xt/put {:xt/id "https://example.org/people/brian"
               ::type "Person"
               ::name "Brian Tanner"}]

    [::xt/put {:xt/id "https://example.org/people/brian/position"
               :juxt.site/type "https://meta.juxt.site/types/permission"
               :juxt.site/action #{"https://example.org/actions/list-trades"}
               :juxt.site/purpose "Trading"
               ::person "https://example.org/people/brian"
               ::role #{"https://example.org/roles/trader"}
               ::desk "https://example.org/desks/equities/bonds"}]

    ;; Betty is also a bond trader
    [::xt/put {:xt/id "https://example.org/people/betty"
               ::type "Person"
               ::name "Betty Jacobs"}]

    [::xt/put {:xt/id "https://example.org/people/betty/position"
               :juxt.site/type "https://meta.juxt.site/types/permission"
               :juxt.site/action #{"https://example.org/actions/list-trades"}
               :juxt.site/purpose "Trading"
               ::person "https://example.org/people/betty"
               ::role #{"https://example.org/roles/trader"}
               ::desk "https://example.org/desks/equities/bonds"}]

    ;; Boris is also a bond trader
    [::xt/put {:xt/id "https://example.org/people/boris"
               ::type "Person"
               ::name "Boris Sokolov"}]

    [::xt/put {:xt/id "https://example.org/people/boris/position"
               :juxt.site/type "https://meta.juxt.site/types/permission"
               :juxt.site/action #{"https://example.org/actions/list-trades"}
               :juxt.site/purpose "Trading"
               ::person "https://example.org/people/boris"
               ::role #{"https://example.org/roles/trader"}
               ::desk "https://example.org/desks/equities/bonds"}]

    ;; Brian and Betty reports to Bernie, head of the bonds desk
    [::xt/put {:xt/id "https://example.org/people/bernie"
               ::type "Person"
               ::name "Bernadette Pulson"}]

    [::xt/put {:xt/id "https://example.org/people/bernie/position"
               :juxt.site/type "https://meta.juxt.site/types/permission"
               :juxt.site/action #{"https://example.org/actions/list-trades"  "https://example.org/actions/get-trader-personal-info"}
               :juxt.site/purpose #{"Trading" "LineManagement"}
               ::person "https://example.org/people/bernie"
               ::role #{"https://example.org/roles/head-of-desk"}
               ::desk "https://example.org/desks/equities/bonds"}]

    ;; Cameron works in one of the bank's control functions, ensuring risk is
    ;; reported to the regulator. He needs to see across the whole equity trade
    ;; population.
    [::xt/put {:xt/id "https://example.org/people/cameron"
               ::type "Person"
               ::name "Cameron White"}]

    [::xt/put {:xt/id "https://example.org/people/cameron/position"
               :juxt.site/type "https://meta.juxt.site/types/permission"
               :juxt.site/action #{"https://example.org/actions/control-list-trades"}
               :juxt.site/purpose "RiskReporting"
               ::person "https://example.org/people/cameron"
               ;; Cameron's role means he can see all trades, but can't see trader details
               ::role #{"https://example.org/roles/regulatory-risk-controller"}}]

    ;; Add some trades
    [::xt/put {:xt/id "https://example.org/trades/S01"
               ::type "Trade"
               ::desk "https://example.org/desks/equities/swaps"
               ::trader "https://example.org/people/sam"
               ::value 95}]

    [::xt/put {:xt/id "https://example.org/trades/B01"
               ::type "Trade"
               ::desk "https://example.org/desks/equities/bonds"
               ::trader "https://example.org/people/brian"
               ::value 20}]

    [::xt/put {:xt/id "https://example.org/trades/B02"
               ::type "Trade"
               ::desk "https://example.org/desks/equities/bonds"
               ::trader "https://example.org/people/brian"
               ::value -30}]

    [::xt/put {:xt/id "https://example.org/trades/B03"
               ::type "Trade"
               ::desk "https://example.org/desks/equities/bonds"
               ::trader "https://example.org/people/betty"
               ::value 180}]])

  (let [db (xt/db *xt-node*)
        action (xt/entity db "https://example.org/actions/list-trades")

        pull-allowed-resources
        (fn [actions subject purpose expected]
          (let [resources
                (authz/pull-allowed-resources
                 db
                 actions
                 {:juxt.site/subject {:xt/id subject}
                  :juxt.site/purpose purpose})]
            (is (= expected (count resources)))
            (assert (= expected (count resources)))
            resources))]

    (assert action)

    (let [actions #{"https://example.org/actions/list-trades"
                    "https://example.org/actions/control-list-trades"}]
      (pull-allowed-resources actions "https://example.org/people/sam" "Trading" 1)
      (pull-allowed-resources actions "https://example.org/people/brian" "Trading" 2)
      (pull-allowed-resources actions "https://example.org/people/betty" "Trading" 1)
      (pull-allowed-resources actions "https://example.org/people/bernie" "Trading" (+ 1 2))
      (pull-allowed-resources actions "https://example.org/people/susie" "Trading" (+ 1 0))
      (pull-allowed-resources actions "https://example.org/people/cameron" "RiskReporting" 4))

    (pull-allowed-resources
     #{"https://example.org/actions/get-trader-personal-info"}
     "https://example.org/people/susie" "LineManagement" 3)

    (pull-allowed-resources
     #{"https://example.org/actions/get-trader-personal-info"}
     "https://example.org/people/bernie" "LineManagement" 4)

    ;; Bernie sees the trades from her desk, see wants more details on the
    ;; trader concerned. Works the same with https://example.org/people/susie

    (let [subject "https://example.org/people/bernie"
          trades
          (authz/pull-allowed-resources
           db
           #{"https://example.org/actions/list-trades"}
           {:juxt.site/subject {:xt/id subject}
            :juxt.site/purpose "Trading"})
          result
          (->>
           (authz/join-with-pull-allowed-resources
            db
            trades
            ::trader
            #{"https://example.org/actions/get-trader-personal-info"}
            {:juxt.site/subject {:xt/id subject}
             :juxt.site/purpose "LineManagement"})
           (map (juxt :xt/id identity))
           (into {}))]

      (is (= "Trade" (get-in result ["https://example.org/trades/B03" ::type]) ))
      (is (= "Betty Jacobs" (get-in result ["https://example.org/trades/B03" ::trader ::name])))
      (is (= 180 (get-in result ["https://example.org/trades/B03" ::value]))))))

;; TODO: Pull syntax integrated with authorization trades

;; There's an idea from @jms that we can upgrade the pull query in XTDB to be
;; action-aware, and thus do a single pull rather than n+1 iterations over
;; fields. A GraphQL query could be compiled to this single pull.

#_(let [db (xt/db *xt-node*)
        eids (map first (xt/q db '{:find [e] :where [[e ::type "Trade"]]}))
        * '*]
    (xt/pull-many db [*
                      ^{:juxt.site/subject "sam" ; this is established on the http request
                        :juxt.site/action "foo" ; this is often given in the OpenAPI definition or GraphQL type schema
                        :juxt.site/purpose "Trading"}
                      {::trader [*]}] eids))

;; TODO: Extend to GraphQL -> pull
;;
;; TODO: Subject access from 'inside' versus 'outside' the perimeter
;;
;; TODO: Continue bootstrapping so Alice can do stuff
;;
;; TODO: Create action list-persons which can be in the scope read:internal ?
;; Or can list-persons as an action be granted to INTERNAL?
;; Does list-persons refer to a resource? I suppose so, it's read:resource on /people/

;; Create a list-persons action
;; Create /people/ resource
;; Grant list-persons on /people/ to Alice
;; Can a GET from Alice to /people/ trigger a list-persons actions?
;; Can a list-persons action be configured to do a a query?

;; Things we'll need in the bootstrap
;;
;; Access token only
;;
;; * A user (can contain anything, just needs to exist)
;; * A OAuth2 registered application representing the 'admin app' (client-id and client-secret) that a caller will use when acquiring a token against the token endpoint
;; * Actions which belong to one or more scopes that permit authorized access to the database
;; * Permissions on the user
;; * Rules that reference permissions, subjects, actions and resources
;;
;; Adding an authorization-provider
;;
;; * An login endpoint that sets up the session and redirects to an issuer
;; * OpenID Authorization Server details (so we can do $issuer/.wellknown/openid-config)
;; * JWKS for the issuer
;; * An identity (:juxt.site/type "Identity") that links to a user (:juxt.site/user) and has a matching iss/sub
;; * OpenID Connect client application details that have been registered with the OpenID Authorization Server
;; * A callback endpoint for the application (this will update the session and set the cookie)
;; * A token endpoint that can be used to acquire tokens


;; Create an access token

#_(postwalk
 (fn [x]
   (if (not (vector? x))
     x
     (let [[k & args] x]
       (case k
         :random-bytes (apply juxt.site.util/random-bytes args)
         :gen-hex-string (apply juxt.site.util/as-hex-str args)
         x))))
 [:let :token-id [:gen-hex-string [:random-bytes 20]]])


#_((t/join-fixtures [xt-fixture])
 (fn []
   (let [CREATE_ACCESS_TOKEN_ACTION
         {:xt/id "https://example.org/actions/create-access-token"
          :juxt.site/type "https://meta.juxt.site/types/action"

          :juxt.site.malli/args-schema
          [:tuple
           [:map
            [:xt/id [:re "urn:site:access-token:(.*)"]]
            [:juxt.site/type [:= "AccessToken"]]
            [:juxt.site/subject [:= [:juxt.site/resolve :juxt.site/subject]]]]]

          :juxt.site/process
          '[
            ;; postwalk would be a very useful way of creating a lisp-like
            ;; evaluator here
            [:gen-hex-string :token-id 20]
            [:add-prefix :token-id "urn:site:access-token:"]
            [:juxt.site.process/update-in
             [0] merge
             {:xt/id [:juxt.site/resolve :token-id]
              :juxt.site/type "AccessToken"
              :juxt.site/subject [:juxt.site/resolve :juxt.site/subject]}]
            [:juxt.site.malli/validate]
            [::xt/put]]

          :juxt.site/rules
          '[[(allowed? subject action resource permission)
             [permission ::person person]
             [subject ::person person]
             [person ::type "Person"]]]}]

     (submit-and-await!
      [
       ;; Actors
       [::xt/put ALICE]

       ;; Subjects
       [::xt/put ALICE_SUBJECT]

       ;; Actions
       [::xt/put CREATE_ACCESS_TOKEN_ACTION]

       ;; Permissions
       [::xt/put
        {:xt/id "https://example.org/permissions/alice/create-access-token"
         :juxt.site/type "https://meta.juxt.site/types/permission"
         ::person (:xt/id ALICE)
         :juxt.site/action (:xt/id CREATE_ACCESS_TOKEN_ACTION)
         :juxt.site/purpose nil}]

       ;; Functions
       [::xt/put (authz/install-do-action-fn)]])

     (let [tmr
           (authz/do-action
            (:juxt.site/xt-node *xt-node*)
            {:juxt.site/subject (:xt/id ALICE_SUBJECT)}
            (:xt/id CREATE_ACCESS_TOKEN_ACTION)
            {:juxt.site/client :client})
           db (xt/db *xt-node*)]

       tmr

       (xt/entity db (get-in tmr [:juxt.site/puts 0]))))))

;; TODO: Build back access-token concept, apps, scopes and filtering of available
;; actions.

#_((t/join-fixtures [xt-fixture])
 (fn []
   ))


(def site-prefix "https://test.example.com")

(defn make-action
  [action-id]
  {:xt/id (str site-prefix "/actions/" action-id)
   :juxt.site/type "https://meta.juxt.site/types/action"
   :juxt.site/scope "read:resource"
   :juxt.site/rules
   [['(allowed? subject action resource permission)
     ['permission :xt/id]]]})

(deftest actions->rules-test
  (testing "When there are no actions in the db for lookup, returns empty result"
    (is (empty? (authz/actions->rules (xt/db *xt-node*) #{"https://test.example.com/actions/employee"}))))

  (submit-and-await! [[::xt/put (make-action "employee")]])
  (submit-and-await! [[::xt/put (update (make-action "contractor") :juxt.site/rules conj '[(include? e action)
                                                                                           [e :type :contractor]])]])

  (testing "When there are no actions specified for lookup, returns empty result"
    (is (empty? (authz/actions->rules (xt/db *xt-node*) #{}))))

  (testing "When a single action is specified for lookup, returns the single result, with an action rule appended"
    (is (= #{'[(allowed? subject action resource permission)
               [permission :xt/id]
               [action :xt/id "https://test.example.com/actions/employee"]]}
           (set
            (authz/actions->rules
             (xt/db *xt-node*)
             #{"https://test.example.com/actions/employee"}))))
    (is (= #{'[(allowed? subject action resource permission)
               [permission :xt/id]
               [action :xt/id "https://test.example.com/actions/contractor"]]
             '[(include? e action)
               [e :type :contractor]
               [action :xt/id "https://test.example.com/actions/contractor"]]}
           (set
            (authz/actions->rules
             (xt/db *xt-node*)
             #{"https://test.example.com/actions/contractor"})))))

  (testing "When a multiple actions are specified for lookup, returns multiple results, each with an action rule appended"
    (is (= #{'[(allowed? subject action resource permission)
               [permission :xt/id]
               [action :xt/id "https://test.example.com/actions/employee"]]
             '[(allowed? subject action resource permission)
               [permission :xt/id]
               [action :xt/id "https://test.example.com/actions/contractor"]]
             '[(include? e action)
               [e :type :contractor]
               [action :xt/id "https://test.example.com/actions/contractor"]]}
           (set
            (authz/actions->rules
             (xt/db *xt-node*)
             #{"https://test.example.com/actions/employee"
               "https://test.example.com/actions/contractor"})))))

  (testing "When an action is specified that does not exist in the db ignores that entry"
    (is (empty? (authz/actions->rules (xt/db *xt-node*) #{"https://test.example.com/actions/project"})))
    (is (= #{'[(allowed? subject action resource permission)
               [permission :xt/id]
               [action :xt/id "https://test.example.com/actions/employee"]]}
           (set
            (authz/actions->rules
             (xt/db *xt-node*)
             #{"https://test.example.com/actions/project"
               "https://test.example.com/actions/employee"}))))))
