(ns ^:skip-wiki clojure.core.typed.name-env
  (:require [clojure.core.typed.type-rep :as r]
            [clojure.core.typed.utils :as u]
            [clojure.core.typed.datatype-env :as dtenv]
            [clojure.core.typed.rclass-env :as rcls]
            [clojure.core.typed.protocol-env :as prenv]
            [clojure.core.typed.declared-kind-env :as kinds]
            [clojure.core.typed.current-impl :as impl]
            [clojure.core.typed :as t :refer [fn> ann when-let-fail def-alias ann-many]])
  (:import (clojure.lang Symbol IPersistentMap Keyword)))

(def-alias NameEnv
  "Environment mapping names to types. Keyword values are special."
  (IPersistentMap Symbol (U Keyword r/TCType)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type Name Env

(ann-many Keyword 
          declared-name-type 
          protocol-name-type 
          datatype-name-type)

(def declared-name-type ::declared-name)
(def protocol-name-type ::protocol-name)
(def datatype-name-type ::datatype-name)

(ann temp-binding Keyword)
(def temp-binding ::temp-binding)

(t/tc-ignore
(doseq [k [declared-name-type protocol-name-type datatype-name-type]]
  (derive k temp-binding))
  )

(defmacro with-clj-name-env [& body]
  `(binding [*current-name-env* CLJ-TYPE-NAME-ENV]
     ~@body))

(defmacro with-cljs-name-env [& body]
  `(binding [*current-name-env* CLJS-TYPE-NAME-ENV]
     ~@body))

(ann ^:no-check name-env? [Any -> Any])
(def name-env? (u/hash-c? (every-pred (some-fn namespace 
                                               #(some #{\.} (str %)))
                                      symbol?)
                          (some-fn r/Type? #(isa? % temp-binding))))


(ann *current-name-env* (U nil (t/Atom1 NameEnv)))
(def ^:dynamic *current-name-env* nil)

(t/tc-ignore
(set-validator! #'*current-name-env* (some-fn nil? #(instance? clojure.lang.Atom %)))
)

(ann ^:no-check CLJ-TYPE-NAME-ENV (t/Atom1 NameEnv))
(defonce CLJ-TYPE-NAME-ENV (atom {} :validator name-env?))

(ann ^:no-check CLJS-TYPE-NAME-ENV (t/Atom1 NameEnv))
(defonce CLJS-TYPE-NAME-ENV (atom {} :validator name-env?))

(ann assert-name-env [-> nil])
(defn assert-name-env []
  (assert *current-name-env* "No name environment bound"))

(ann update-name-env! [NameEnv -> nil])
(defn update-name-env! [nme-env]
  (assert-name-env)
  (when-let-fail [e *current-name-env*]
    (swap! e (fn> [n :- (U nil NameEnv)]
               {:pre [n]}
               (merge n nme-env))))
  nil)

(ann reset-name-env! [NameEnv -> nil])
(defn reset-name-env! [nme-env]
  (assert-name-env)
  (when-let-fail [e *current-name-env*]
    (reset! e nme-env))
  nil)

(ann ^:no-check get-type-name [Any -> (U nil Keyword r/TCType)])
(defn get-type-name 
  "Return the name with var symbol sym.
  Returns nil if not found."
  [sym]
  (assert-name-env)
  (when-let-fail [e *current-name-env*]
    (@e sym)))

(ann ^:no-check add-type-name [Symbol (U Keyword r/TCType) -> nil])
(defn add-type-name [sym ty]
  (assert-name-env)
  (when-let-fail [e *current-name-env*]
    (swap! e
           (fn> [e :- (U nil NameEnv)]
            {:pre [e]}
            (assoc e sym (if (r/Type? ty)
                           (vary-meta ty assoc :from-name sym)
                           ty)))))
  nil)

(ann ^:no-check declare-name* [Symbol -> nil])
(defn declare-name* [sym]
  {:pre [(symbol? sym)
         (namespace sym)]}
  (add-type-name sym declared-name-type)
  nil)

(ann declared-name? [Any -> Any])
(defn declared-name? [sym]
  (= declared-name-type (get-type-name sym)))

(ann ^:no-check declare-protocol* [Symbol -> nil])
(defn declare-protocol* [sym]
  {:pre [(symbol? sym)
         (some #(= \. %) (str sym))]}
  (add-type-name sym protocol-name-type)
  nil)

(ann ^:no-check declare-datatype* [Symbol -> nil])
(defn declare-datatype* [sym]
  (add-type-name sym datatype-name-type)
  nil)

(ann ^:no-check resolve-name* [Symbol -> r/TCType])
(defn resolve-name* [sym]
  {:post [(r/Type? %)]}
  (let [t (get-type-name sym)
        tfn ((some-fn dtenv/get-datatype 
                      prenv/get-protocol
                      (impl/impl-case :clojure rcls/get-rclass :cljs (constantly nil)) 
                      ; during the definition of RClass's that reference
                      ; themselves in their definition, a temporary TFn is
                      ; added to the declared kind env which is enough to determine
                      ; type rank and variance.
                      kinds/declared-kind-or-nil) 
             sym)]
    (if tfn
      tfn
      (cond
        (= protocol-name-type t) (prenv/resolve-protocol sym)
        (= datatype-name-type t) (dtenv/resolve-datatype sym)
        (= declared-name-type t) (throw (IllegalArgumentException. (str "Reference to declared but undefined name " sym)))
        (r/Type? t) (vary-meta t assoc :source-Name sym)
        :else (u/int-error (str "Cannot resolve name " (pr-str sym)
                                (when t
                                  (str " (Resolved to instance of)" (pr-str (class t))))))))))
