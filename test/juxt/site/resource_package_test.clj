;; Copyright © 2022, JUXT LTD.

(ns juxt.site.resource-package-test
  (:require
   [clojure.test :refer [deftest is are use-fixtures]]
   [juxt.site.resource-package :as pkg]
   [juxt.site.repl :as repl]
   [juxt.test.util
    :refer [with-system-xt
            with-fixtures with-handler
            with-bootstrapped-resources]]))

(use-fixtures :each with-system-xt with-handler with-bootstrapped-resources)

(deftest install-resource-packages-test []
  (is
   (with-fixtures
     (pkg/install-bootstrap-package! {})
     (pkg/install-package! (pkg/load-package-from-filesystem "resources/core"))
     (pkg/install-package! (pkg/load-package-from-filesystem "resources/whoami"))
     (repl/ls))))
