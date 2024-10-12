(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'treo/treo)
(def version "0.2.2")
(def class-dir "target/classes")

(defn- pom-template [version]
  [[:description "Simple routing library that hooks into namespace functions."]
   [:url "https://github.com/sbreatnach/treo"]
   [:licenses
    [:license
     [:name "Eclipse Public License"]
     [:url "https://www.eclipse.org/legal/epl/epl-v20.html"]]]
   [:developers
    [:developer
     [:name "Shane Breatnach"]]]
   [:scm
    [:url "https://github.com/sbreatnach/treo"]
    [:connection "scm:git:https://github.com/sbreatnach/treo.git"]
    [:developerConnection "scm:git:ssh:git@github.com:sbreatnach/treo.git"]
    [:tag (str "v" version)]]])

(defn- jar-opts [opts]
  (assoc opts
          :lib lib   :version version
          :jar-file  (format "target/%s-%s.jar" lib version)
          :basis     (b/create-basis {})
          :class-dir class-dir
          :target    "target"
          :src-dirs  ["src"]
          :pom-data  (pom-template version)))

(defn create-jar "Build the JAR" [opts]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding JAR..." (:jar-file opts))
    (b/jar opts)))

(defn install "Install the JAR locally." [opts]
  (let [opts (jar-opts opts)]
    (b/install opts))
  opts)

(defn deploy "Deploy the JAR to Clojars." [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (create-jar opts)
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
