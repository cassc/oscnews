(defproject oscnews "0.1.0-SNAPSHOT"
  :description "Download osc news as epub"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/data.zip "0.1.1"]
                 ;; [clj-http "0.9.2" :exclusions [cheshire crouton ]]
                 [http-kit "2.1.16"]
                 [hiccup "1.0.5"]
                 [hickory "0.5.3"
                  :exclusions [org.clojure/clojurescript]]
                 [org.clojure/data.xml "0.0.8"]]
  :jar-exclusions [#".*java$" #"^config\.edn$" #".*\.clj$"]
  :profiles

  {:uberjar {:aot :all}}
  :main oscnews.core)
