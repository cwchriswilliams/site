;; Copyright © 2022, JUXT LTD.

(ns juxt.pass.alpha.process2
  (:require
   [clojure.walk :refer [postwalk]]
   [juxt.site.alpha.util :refer [random-bytes as-hex-str]]
   [juxt.pass.alpha :as-alias pass]
   [juxt.pass.alpha.malli :as-alias pass.malli]
   [malli.core :as m]
   [malli.error :a me]
   [xtdb.api :as xt]))

(defmulti processing-step (fn [_ [k] ctx] k))

;; ctx must contain :db
(defmethod processing-step ::validate [m [_ schema] ctx]
  (if-not (m/validate schema m)
    (throw
     (ex-info
      "Failed validation check"
      (m/explain schema m)))
    m))

(defmethod processing-step ::nest [m [_ k] ctx]
  {k m})

(defmethod processing-step ::merge [m [_ m2] ctx]
  (merge m m2))

(defmethod processing-step ::dissoc [m [_ & ks] ctx]
  (apply dissoc m ks))

(defmethod processing-step ::match-identity-on-password
  [m [_ k {:keys [username-in-identity-key username-location
                  password-in-identity-key password-location]}]
   ctx]
  (assert (:db ctx))
  (let [identity
        (first
         (map first
              (xt/q
               (:db ctx)
               {:find '[e]
                :where [
                        ['e username-in-identity-key 'username]
                        ['e password-in-identity-key 'password-hash]
                        ['(crypto.password.bcrypt/check password password-hash)]]
                :in '[username password]}
               (get-in m username-location)
               (get-in m password-location))))]
    (cond-> m
      identity (assoc k identity))))

(defmethod processing-step ::db-single-put
  [m _ _]
  [[:xtdb.api/put m]])

(defn process [steps seed ctx]
  (reduce (fn [acc step] (processing-step acc step ctx)) seed (filter some? steps)))
