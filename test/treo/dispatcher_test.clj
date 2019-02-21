(ns treo.dispatcher-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :as rmock]
            [ring.util.response :as rresp]
            [treo.dispatcher :as dispatcher])
  (:import [java.net HttpURLConnection]
           [clojure.lang ExceptionInfo]))

(deftest test-create-route-regex
  (testing "Expected regexes are created based on arguments"
    (is (= "^/api/v1/?$"
           (str (dispatcher/create-route-regex "/api/v1"))))
    (is (= "^/api/v1/?$"
           (str (dispatcher/create-route-regex "/api/v1" ""))))
    (is (= "^/api/v1/?$"
           (str (dispatcher/create-route-regex "/api/v1" nil))))
    (is (= "^/api/v1/testresource/?$"
           (str (dispatcher/create-route-regex "/api/v1" "testresource"))))
    (is (= "^/api/v1/testresource/apply/?$"
           (str (dispatcher/create-route-regex "/api/v1" "testresource" "apply"))))))

(deftest test-uri-regex-route
  (testing "Validate that generated plain handler has expected results"
    (let [route-handler (dispatcher/uri-regex-route #"^/api/v1/test/?$"
                                                    (fn [request] request))]
      (is (= {:server-port 80
              :server-name "localhost"
              :remote-addr "localhost"
              :uri "/api/v1/test"
              :protocol "HTTP/1.1"
              :scheme :http
              :request-method :get
              :headers {"host" "localhost"}
              :treo/route {:groups []
                           :named-groups {}}}
             (route-handler (rmock/request :get "/api/v1/test"))))
      (is (nil? (route-handler (rmock/request :get "/api/v1/nottest"))))))
  (testing "Validate that generated grouped handler has expected results"
    (let [route-handler (dispatcher/uri-regex-route #"^/api/v1/test/(\d+)/?$"
                                                    (fn [request] request))]
      (is (= {:server-port 80
              :server-name "localhost"
              :remote-addr "localhost"
              :uri "/api/v1/test/34/"
              :protocol "HTTP/1.1"
              :scheme :http
              :request-method :get
              :headers {"host" "localhost"}
              :treo/route {:groups ["34"], :named-groups {}}}
             (route-handler (rmock/request :get "/api/v1/test/34/"))))
      (is (nil? (route-handler (rmock/request :get "/api/v1/test"))))))
  (testing "Validate that generated named group handler has expected results"
    (let [route-handler (dispatcher/uri-regex-route #"^/api/v1/test/(?<id>\d+)/?$"
                                                    (fn [request] request))]
      (is (= {:server-port 80
              :server-name "localhost"
              :remote-addr "localhost"
              :uri "/api/v1/test/34/"
              :protocol "HTTP/1.1"
              :scheme :http
              :request-method :get
              :headers {"host" "localhost"}
              :treo/route {:groups ["34"], :named-groups {:id "34"}}}
             (route-handler (rmock/request :get "/api/v1/test/34/"))))
      (is (nil? (route-handler (rmock/request :get "/api/v1/test")))))))

(deftest test-create-request-method-handler
  (testing "Created handler matches against request method as expected"
    (let [handler (dispatcher/create-request-method-handler
                   {:get (fn [_] (rresp/response "getting"))
                    :post (fn [_] (rresp/response "creating"))})]
      (is (= {:status HttpURLConnection/HTTP_OK
              :headers {}
              :body "getting"}
             (handler (rmock/request :get "/api/v1/test"))))
      (is (= {:status HttpURLConnection/HTTP_OK
              :headers {}
              :body "creating"}
             (handler (rmock/request :post "/api/v1/test"))))
      (is (= {:status HttpURLConnection/HTTP_BAD_METHOD
              :headers {}
              :body nil}
             (handler (rmock/request :delete "/api/v1/test"))))
      (is (= {:status HttpURLConnection/HTTP_BAD_METHOD
              :headers {}
              :body nil}
             (handler (rmock/request :patch "/api/v1/test"))))
      (is (= {:status HttpURLConnection/HTTP_BAD_METHOD
              :headers {}
              :body nil}
             (handler (rmock/request :put "/api/v1/test"))))))
  (testing "Created handler rejects with configured body for invalid request method"
    (let [handler (dispatcher/create-request-method-handler
                   {:get (fn [_] (rresp/response "getting"))}
                   {:method-not-allowed-body {:detail "Invalid request"}})]
      (is (= {:status HttpURLConnection/HTTP_OK
              :headers {}
              :body "getting"}
             (handler (rmock/request :get "/api/v1/test"))))
      (is (= {:status HttpURLConnection/HTTP_BAD_METHOD
              :headers {}
              :body {:detail "Invalid request"}}
             (handler (rmock/request :post "/api/v1/test")))))))

(defn gen-test-ns
  "Generates test namespace with the given name and pairs of fn
  name/implementation. Returns the symbol to the newly generated namespace."
  [name & ns-fns]
  (let [test-ns-symbol (symbol (str "com.example." name))
        test-ns (create-ns test-ns-symbol)]
    (doseq [[fn-sym fn-val] ns-fns]
      (intern test-ns fn-sym fn-val))
    test-ns-symbol))

(deftest test-create-namespace-handler
  (testing "Created namespace handler matches against namespace functions as expected"
    (let [test-ns (gen-test-ns "handler1"
                               ['create (fn [_] (rresp/response "create"))])
          handler (dispatcher/create-namespace-handler test-ns)]
      (is (= {:status HttpURLConnection/HTTP_BAD_METHOD
              :headers {}
              :body nil}
             (handler (rmock/request :get "/api/v1/test"))))
      (is (= {:status HttpURLConnection/HTTP_OK
              :headers {}
              :body "create"}
             (handler (rmock/request :post "/api/v1/test"))))
      (is (= {:status HttpURLConnection/HTTP_BAD_METHOD
              :headers {}
              :body nil}
             (handler (rmock/request :put "/api/v1/test"))))
      (is (= {:status HttpURLConnection/HTTP_BAD_METHOD
              :headers {}
              :body nil}
             (handler (rmock/request :patch "/api/v1/test"))))
      (is (= {:status HttpURLConnection/HTTP_BAD_METHOD
              :headers {}
              :body nil}
             (handler (rmock/request :delete "/api/v1/test"))))))
  (testing "Created namespace handler matches against subset of namespace functions as expected"
    (let [test-ns (gen-test-ns "handler1"
                               ['create (fn [_] (rresp/response "create"))])
          handler (dispatcher/create-namespace-handler test-ns {:methods #{:post :get}})]
      (is (= {:status HttpURLConnection/HTTP_BAD_METHOD
              :headers {}
              :body nil}
             (handler (rmock/request :get "/api/v1/test"))))
      (is (= {:status HttpURLConnection/HTTP_OK
              :headers {}
              :body "create"}
             (handler (rmock/request :post "/api/v1/test"))))
      (is (nil? (handler (rmock/request :put "/api/v1/test"))))
      (is (nil? (handler (rmock/request :patch "/api/v1/test"))))
      (is (nil? (handler (rmock/request :delete "/api/v1/test")))))))

(deftest test-namespace-route-generator
  (let [generator (dispatcher/namespace-route-generator "/api/v1")]
    (testing "Validate route sequence generated functions as expected"
      (let [test-ns (gen-test-ns "handler2"
                                 ['create (fn [_] (rresp/response "create"))])
            route-handler (generator ["testresource"] test-ns)]
        (is (= {:status HttpURLConnection/HTTP_OK
                :headers {}
                :body "create"}
               (route-handler (rmock/request :post "/api/v1/testresource"))))))
    (testing "Validate string route generated functions as expected"
      (let [test-ns (gen-test-ns "handler2"
                                 ['create (fn [_] (rresp/response "create"))])
            route-handler (generator "testresource" test-ns)]
        (is (= {:status HttpURLConnection/HTTP_OK
                :headers {}
                :body "create"}
               (route-handler (rmock/request :post "/api/v1/testresource"))))))
    (testing "Validate null route generated functions as expected"
      (let [test-ns (gen-test-ns "handler2"
                                 ['create (fn [_] (rresp/response "create"))])
            route-handler (generator nil test-ns)]
        (is (= {:status HttpURLConnection/HTTP_OK
                :headers {}
                :body "create"}
               (route-handler (rmock/request :post "/api/v1"))))))
    (testing "Verify an invalid route results in error thrown"
      (let [test-ns (gen-test-ns "handler2"
                                 ['create (fn [_] (rresp/response "create"))])]
        (is (thrown? ExceptionInfo (generator 3534 test-ns)))))
    (testing "Validate route generated with middleware functions as expected"
      (let [invoke-count (atom 0)
            test-ns (gen-test-ns "handler2"
                                 ['create (fn [request]
                                            (swap! invoke-count inc)
                                            (rresp/response
                                             (assoc (select-keys request [:test1 :test2])
                                                    :invoke-count @invoke-count)))])
            test-middleware1 (fn [handler]
                               (fn [request]
                                 (handler (assoc request :test1 "value1"))))
            test-middleware2 (fn [handler]
                               (fn [request]
                                 (handler (assoc request :test2 "value2"))))
            route-handler (generator ["testresource"] test-ns
                                     test-middleware1 test-middleware2)]
        (is (= {:status HttpURLConnection/HTTP_OK
                :headers {}
                :body {:test1 "value1", :test2 "value2" :invoke-count 1}}
               (route-handler (rmock/request :post "/api/v1/testresource"))))))))
