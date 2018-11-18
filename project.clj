(defproject com.glicsoft/treo "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.logging "0.4.1"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [ring/ring-core "1.7.1"]]
  :profiles {:dev {:resource-paths ["test/resources"]
                   :dependencies [[ring/ring-mock "0.3.2"]]}})
