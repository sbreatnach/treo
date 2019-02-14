(ns treo.dispatcher
  (:require [clojure.tools.logging :as log]
            [ring.util.response :as rresp])
  (:import [java.net HttpURLConnection]))

(def name-regex
  #"\(\?<([a-zA-Z][a-zA-Z0-9]+)>")

(defn named-groups
  "Returns sequence of named groups in the given pattern"
  [pattern]
  (next (re-find name-regex (str pattern))))

(defn create-route-regex
  "Constructs a route regular expression for the given parts of a route.
  Automatically adds support for URI prefix and trailing slash."
  [prefix & route-parts]
  (let [all-route-parts (conj (vec (remove empty? route-parts)) "?$")
        prefix (if (.endsWith prefix "/") prefix (str prefix "/"))]
    (re-pattern (apply str "^" prefix
                       (interpose "/" all-route-parts)))))

(defn uri-regex-route
  "Creates a route handler that dispatches based on the given URI regex.
  Any groups are injected into the handler's request."
  [uri-regex handler]
  (let [group-names (or (named-groups uri-regex) [])]
    (fn [{:keys [uri] :as request}]
      (let [matcher (re-matcher uri-regex uri)]
        (when-let [matches (re-find matcher)]
          (log/debug "Matched request" uri "to regex" uri-regex)
          (let [groups (if (string? matches) [] (next matches))
                named-matches (flatten
                               (map (fn [^String name]
                                      [(keyword name) (.group matcher name)]) group-names))
                named-groups (if (empty? named-matches)
                               {} (apply hash-map named-matches))
                new-request (assoc request
                                   :treo/route {:groups groups
                                                :named-groups named-groups})]
            (handler new-request)))))))

(def ns-method-fns
  "Default map of request methods to the relevant namespace function. May be
  rebound to modify the defaults globally or per usage."
  ^:dynamic
  {:get 'show
   :post 'create
   :put 'change
   :patch 'change
   :delete 'delete})

(defn namespaced-handler-fn
  "Returns the underlying method function from the namespace handler for the
  given Ring request. nil is returned if no matched function found."
  [handler-wrapper {:keys [request-method]}]
  (-> handler-wrapper meta :treo/wrapped-fns (get request-method)))

(defn create-namespace-handler
  "Creates a multi-method request handler based on the given namespace. Uses
  function name conventions to determine which HTTP methods are supported.

  Invokes the relevant function when request is processed. Responds with HTTP
  Bad Method if request method doesn't have a matching function in the namespace.

  May optionally specify the supported request methods and the function
  names that map to them.
  May optionally specify the response body for the Bad Method response, which
  defaults to an empty body."
  [handler-ns & [{:keys [methods method-fns method-not-allowed-body]
                  :or {method-fns ns-method-fns}}]]
  ;; attempt to pre-load the namespace if it's not available
  (when (nil? (find-ns handler-ns))
    (require handler-ns))
  (let [methods (or methods (set (keys method-fns)))
        found-fns (into {} (for [[key value] (select-keys method-fns methods)
                                 :let [method-fn (ns-resolve handler-ns value)]
                                 :when method-fn]
                             [key method-fn]))]
    ;; inject matched namespace methods fns into generated request handler fn
    (with-meta
      (fn [{:keys [request-method] :as request}]
        (if-let [found-fn (get found-fns request-method)]
          (do
            (log/trace "Matched" handler-ns "with method" request-method)
            (let [response (found-fn request)]
              (log/trace "Response" response)
              response))

          (when (contains? methods request-method)
            (log/trace "Matched" handler-ns
                       "but no function for method" request-method)
            (rresp/status (rresp/response method-not-allowed-body)
                          HttpURLConnection/HTTP_BAD_METHOD))))
      {:treo/wrapped-fns found-fns})))

(defn threading-fn
  "Function version of the threading -> macro that applies the variable sequence
  of functions consecutively on the given initial value"
  [value & fns]
  (loop [value value
         fns fns]
    (if (seq fns)
      (let [cur-fn (first fns)
            threaded (cur-fn value)]
        (recur threaded (next fns)))
      value)))

(defn namespace-route-generator
  "Generates convenience function for combining regex routes with namespace
  handlers. May optionally provide a prefix for the routes the function
  generates.

  Returns function that takes two arguments: a string or sequence of strings
  representing the REST resource and any arguments, and a namespace symbol
  defining the implemented HTTP methods. Function also takes a variable number
  of middleware functions that are applied to the namespace handler beforehand
  but after the route is determined."
  [& [prefix]]
  (let [prefix (or prefix "")]
    (fn [raw-route-parts handler-ns & middleware]
      (let [route-parts (cond
                          (or (nil? raw-route-parts) (string? raw-route-parts))
                          [raw-route-parts]

                          (sequential? raw-route-parts)
                          raw-route-parts

                          :else
                          (throw (ex-info "Invalid route configuration, must be a string or sequence of strings"
                                          {:invalid-route raw-route-parts})))
            middleware-fns (concat
                            ;; converts the namespace into a standard Ring handler
                            [create-namespace-handler]
                            ;; applies given middleware to the root handler
                            middleware
                            ;; before anything else, match based on route regex
                            [(partial uri-regex-route
                                      (apply create-route-regex
                                             prefix
                                             route-parts))])]
        (log/debug "Creating handler for" route-parts
                   ", prefix:" prefix
                   ", namespace:" handler-ns
                   ", middleware:" middleware)
        (apply threading-fn handler-ns middleware-fns)))))

; nicked from compojure source to avoid excessive dependencies

(defn routing
  "Apply a list of routes to a Ring request map."
  [request & handlers]
  (some #(% request) handlers))

(defn routes
  "Create a Ring handler by combining several handlers into one."
  [& handlers]
  #(apply routing % handlers))
