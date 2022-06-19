;; Copyright © 2022, JUXT LTD.

(ns juxt.flip.core-test
  (:require
   [clojure.test :refer [deftest is use-fixtures testing] :as t]
   [crypto.password.bcrypt :as password]
   [juxt.flip.alpha.core :as flip]
   [juxt.site.alpha.repl :as repl]
   [juxt.test.util :refer [with-system-xt *xt-node*]]
   [juxt.pass.alpha :as-alias pass]
   [juxt.pass.alpha.authorization :as authz]
   [juxt.http.alpha :as-alias http]
   [juxt.site.alpha :as-alias site]
   [ring.util.codec :as codec]
   [xtdb.api :as xt]))

(use-fixtures :each with-system-xt)

;; A login to Site.

;; A user sends in a map (containing their username and password, for a login
;; form).

;; We validate this map, validating they have given us sensible input.

;; We use the values in this map to find/match a user in our system.

;; If we find such a user, we construct a 'subject' to represent them, pointing
;; to their identity.

;; We 'put' this subject into the database, this subject will be forever
;; associated with the user when they access the system, and used in
;; authorization rules for the action the user performs.

(comment
  ((t/join-fixtures [with-system-xt])
   (fn []
     (repl/put!
      {:xt/id "https://site.test/user-identities/alice"
       ::pass/username "alice"
       ::pass/password-hash (password/encrypt "garden")})

     (let [
           eval-quotation-results
           (flip/eval-quotation
            (list)

            '(
              :juxt.site.alpha/received-representation env
              :juxt.http.alpha/body of
              bytes-to-string

              juxt.flip.alpha/form-decode

              (validate
               [:map
                ["username" [:string {:min 1}]]
                ["password" [:string {:min 1}]]])

              dup

              "username"
              of
              >lower   ; Make usernames case-insensitive as per OWASP guidelines

              swap
              "password"
              of
              swap

              ;; We now have a stack with: <user> <password>

              (juxt.flip.alpha.xtdb/q
               (find-matching-identity-on-password-query
                {:username-in-identity-key ::pass/username
                 :password-hash-in-identity-key ::pass/password-hash}))

              first first

              (if*
                  [::pass/user-identity
                   juxt.flip.alpha.hashtables/associate

                   "https://meta.juxt.site/pass/subject"
                   :juxt.site.alpha/type
                   rot set-at

                   ;; Make subject
                   (random-bytes 10) as-hex-string
                   (str "https://site.test/subjects/")
                   :xt/id rot set-at

                   ;; Create the session, linked to the subject
                   dup :xt/id of
                   ::pass/subject juxt.flip.alpha.hashtables/associate

                   ;; Now we're good to wrap up the subject in a tx-op
                   swap xtdb.api/put swap

                   (make-nonce 16)
                   "https://site.test/sessions/" str
                   :xt/id
                   rot set-at
                   "https://meta.juxt.site/pass/session"
                   ::site/type
                   rot set-at

                   dup :xt/id of
                   ::pass/session juxt.flip.alpha.hashtables/associate

                   swap xtdb.api/put swap

                   (make-nonce 16)
                   swap
                   over
                   ::pass/session-token
                   rot set-at

                   swap

                   (str "https://site.test/session-tokens/")
                   :xt/id
                   rot set-at

                   "https://meta.juxt.site/pass/session-token"
                   ::site/type
                   rot set-at
                   xtdb.api/put

                   ;; We now create the following quotation:
                   #_[(of :ring.response/headers dup)
                      (if* [] [<array-map>]) ; if no headers, use an empty map
                      (juxt.flip.alpha/assoc "set-cookie" "id=<session token>; Path=/; Secure; HttpOnly; SameSite=Lax")
                      (juxt.flip.alpha/assoc :ring.response/headers)]

                   ;; Get the session token back and turn into a quotation
                   dup second :juxt.pass.alpha/session-token of
                   "id=" str
                   "; Path=/; Secure; HttpOnly; SameSite=Lax" swap str

                   (symbol "juxt.flip.alpha/assoc") swap
                   "set-cookie"
                   _3array >list

                   ;; Now quote the initial two lines
                   [dup (of :ring.response/headers)
                    (if* [] [<array-map>]) ; if no headers, use an empty map
                    ]

                   ;; Push the '(juxt.flip.alpha/assoc "set-cookie" ...) line onto the program
                   push

                   ;; Finish the rest of the program
                   (symbol "juxt.flip.alpha/assoc")
                   :ring.response/headers
                   _2array >list
                   swap push

                   ;; Turn into a apply-to-request-context quotation
                   :juxt.site.alpha/apply-to-request-context
                   swap
                   _2array >vector


                   ;; Finally we pull out and use the return_to query parameter
                   :ring.request/query env
                   (if*
                       [juxt.flip.alpha/form-decode
                        "return-to" of
                        (if*
                            [(symbol "juxt.flip.alpha/assoc") swap
                             "location"
                             _3array >list

                             [dup (of :ring.response/headers)
                              ;; if no headers, use an empty map
                              (if* [] [<array-map>])]

                             push

                             ;; Finish the rest of the program
                             (symbol "juxt.flip.alpha/assoc")
                             :ring.response/headers
                             _2array >list
                             swap push

                             ;; Turn into a apply-to-request-context quotation
                             :juxt.site.alpha/apply-to-request-context
                             swap
                             _2array >vector]
                            [])

                        ;; A quotation that will set a status 302 on the request context
                        (juxt.site.alpha/apply-to-request-context
                         [(juxt.flip.alpha/assoc 302 :ring.response/status)])]

                       [])]

                ;; else
                  [(throw (ex-info "Login failed" {:ring.response/status 400}))]))

            {::site/db (xt/db *xt-node*)
             ::site/received-representation
             {::http/body
              (.getBytes
               (codec/form-encode
                {"username" "aliCe"
                 "password" "garden"
                 "csrf-tok" "123"}))

              ::http/content-type "application/x-www-form-urlencoded"
              }
             :ring.request/query "return-to=/document.html"
             :ring.response/headers {"server" "jetty"}}

            )]

       eval-quotation-results

       #_(authz/apply-request-context-operations
          req
          (->>
           eval-quotation-results
           (filter (fn [[op]]  (= op :juxt.site.alpha/apply-to-request-context)))))))))


(comment
  (flip/eval-quotation
   (list {:ring.response/headers {"a" "b"} :ring.response/status 200 :foo :bar})
   '(dup :ring.response/headers of [<array-map>] [] if* "bar" swap "foo" swap set-at swap :ring.response/headers swap set-at)
   {}))

#_(comment
    (flip/eval-quotation
     (list {:ring.response/headers {"a" "b"} :ring.response/status 200 :foo :bar})
     '(dup :ring.response/headers swap :ring.response/headers of [] [<array-map>] if* "foo" "bar" juxt.flip.alpha/assoc juxt.flip.alpha/assoc)
     {}))

#_(comment
    (flip/eval-quotation
     (list {:ring.response/headers {"a" "b"} :ring.response/status 200 :foo :bar})
     '(dup :ring.response/headers swap (of :ring.response/headers) (if* [] [<array-map>]) "foo" "bar" juxt.flip.alpha/assoc juxt.flip.alpha/assoc)
     {}))

#_(comment
    (flip/eval-quotation
     (list {:ring.response/headers {"a" "b"} :ring.response/status 200 :foo :bar})
     '(dup :ring.response/headers swap (of :ring.response/headers) (if* [] [<array-map>]) "foo" "bar" juxt.flip.alpha/assoc juxt.flip.alpha/assoc)
     {}))


(comment
  (flip/eval-quotation
   (list {:ring.response/headers {"a" "b"} :ring.response/status 200 :foo :bar})
   '(dup :ring.response/headers of [<array-map>] [] if* "bar" swap "foo" swap set-at swap :ring.response/headers swap set-at)
   {}))

(comment
  (flip/eval-quotation
   (list {:ring.response/headers {"a" "b"} :ring.response/status 200 :foo :bar})
   '(dup :ring.response/headers of [<array-map>] [] if* "bar" "foo" juxt.flip.alpha/assoc :ring.response/headers juxt.flip.alpha/assoc)
   {}))

(comment
  (flip/eval-quotation
   (list {:ring.response/headers {"a" "b"} :ring.response/status 200 :foo :bar})
   '(dup :ring.response/headers of (if* [] [<array-map>]) "bar" "foo" juxt.flip.alpha/assoc :ring.response/headers juxt.flip.alpha/assoc)
   {}))

(comment
  (flip/eval-quotation
   (list {:ring.response/headers {"a" "b"} :ring.response/status 200 :foo :bar})
   '(dup
     (of :ring.response/headers)
     (if* [] [<array-map>])
     (juxt.flip.alpha/assoc "foo" "bar")
     (juxt.flip.alpha/assoc :ring.response/headers))
   {}))





#_((t/join-fixtures [with-system-xt])
 (fn []
   (repl/put! {:xt/id "https://site.test/flip/quotations/req-to-edn-body"
               :juxt.flip.alpha/quotation
               '(:juxt.site.alpha/received-representation
                 env
                 ::http/body
                 of
                 bytes-to-string
                 read-edn-string)})
   (flip/eval-quotation
    (list )
    '(
      "https://site.test/flip/quotations/req-to-edn-body" juxt.flip.alpha.xtdb/entity :juxt.flip.alpha/quotation of call

      (validate
       [:map
        [:xt/id [:re "https://site.test/.*"]]
        [:juxt.pass.alpha/user [:re "https://site.test/users/.+"]]
        [:juxt.pass.alpha/username {:optional true} [:re "[A-Za-z0-9]{2,}"]]
        [:juxt.pass.alpha/password-hash {:optional true} [:string]]
        [:juxt.pass.jwt/iss {:optional true} [:re "https://.+"]]
        [:juxt.pass.jwt/sub {:optional true} [:string {:min 1}]]])

      "https://meta.juxt.site/pass/user-identity" :juxt.site.alpha/type assoc

      ;; Lowercase the username, if it exists.
      dup :juxt.pass.alpha/username of (if* [>lower :juxt.pass.alpha/username assoc] [])

      {:get {:juxt.pass.alpha/actions #{"https://site.test/actions/get-user-identity"}}
       :head {:juxt.pass.alpha/actions #{"https://site.test/actions/get-user-identity"}}
       :options {}}
      :juxt.site.alpha/methods
      assoc

      xtdb.api/put)
    {::site/db (xt/db *xt-node*)
     ::site/received-representation
     {::http/body
      (.getBytes
       (pr-str
        {:xt/id "https://site.test/user-identities/alice/basic"
         :juxt.pass.alpha/user "https://site.test/users/alice"
         ;; Perhaps all user identities need this?
         :juxt.pass.alpha/canonical-root-uri "https://site.test"
         :juxt.pass.alpha/realm "Wonderland"
         ;; Basic auth will only work if these are present
         :juxt.pass.alpha/username "ALICE"
         :juxt.pass.alpha/password-hash "asefase"}))

      :ring.requst/query "foo=bar"
      ::http/content-type "application/x-www-form-urlencoded"
      :ring.response/headers {"server" "jetty"}}})))



;; Password checking is very slow compared to swap
;; We must be sure that it is only called once, or we might memoize for performance
(comment
  (time
   (do
     (password/check "garden" "$2a$11$mexf3rW6dmoyhqaFnpzHruNmNV0JPt/YhdAJOdAOf6HjIFRz1NN/e")
     (password/check "garden" "$2a$11$mexf3rW6dmoyhqaFnpzHruNmNV0JPt/YhdAJOdAOf6HjIFRz1NN/e"))))
