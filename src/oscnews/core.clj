(ns oscnews.core
  (:gen-class)
  (:use
   [hiccup.core]
   [hickory.core       :only [parse as-hickory as-hiccup parse-fragment]]
   [hickory.zip        :only [hickory-zip hiccup-zip]]
   [hickory.convert    :only [hickory-to-hiccup]]
   [clojure.java.shell :only [sh]])
  (:require
   [org.httpkit.client :as client]
   [hickory.select     :as s]
   [clojure.zip        :as zip]
   [clojure.data.xml   :as xml]
   [clojure.java.io    :as io]
   [clojure.string     :as cstr])
  (:import
   [java.util Date]
   [java.text SimpleDateFormat]))

;; Utilility fn
(defn zip-str
  "convenience function to parse xml string as clojure datastructure, first seen at nakkaya.com later in clj.zip src"
  [s]
  (zip/xml-zip
   (xml/parse (java.io.ByteArrayInputStream. (.getBytes s)))))


(defn get-filename
  "Get filename with ext from a url"
  [url]
  (second (re-find #".*/(.*\.\w+$?)" url)))

(defn linux?
  "Is current app running on a linux box?"
  []
  (= "linux"
     (clojure.string/lower-case (System/getProperty "os.name"))))

(defn calibre?
  "Test if os is linux and calibre exists"
  []
  (and (linux?) (= 0
                   (:exit (sh "which" "calibre"))
                   (:exit (sh "which" "ebook-convert")))))


(defn- get-status-by-exception
  "Get http status code from an exception of client/request."
  [e]
  (try
    (:status (:object (.getData e)))
    (catch Exception f)))


(defmacro def-httpmethod
  [method]
  `(defn ~method
     ~(str "Issues an client/" method " request."
           "TODO When 503,502 or 403 error occurs, will retry in 5 seconds")
     ~'[url params]
     (Thread/sleep 500) ;; sleep a while to reduce request frequency
     (let [request# ~(symbol (str "client/" (clojure.string/lower-case method)))]
       (prn ~'url ~'params)
       @(request# ~'url ~'params))))

(def-httpmethod GET)
(def-httpmethod POST)


(defn wrap-ignore-exception
  "Ignores any exception caused by calling the wrapped function."
  [f]
  (fn [& args]
    (try
      (apply f args)
      (catch Exception e
        (.printStackTrace e)
        nil))))

;; uris
(def osc-host
  "http://www.oschina.net")

(def osc-rss
  (str osc-host "/news/rss"))

;; 1 all, 2 integration, 3 software, 4 truely all
; http://www.oschina.net/action/api/news_list?catalog=0&pageIndex=0&pageSize=50
; xml:
;;<oschina><catalog>0</catalog><newsCount>0</newsCount><pagesize>10</pagesize><newslist>
;; <news><id>10002406</id><title>Node.js 中实现 HTTP 206 内容分片</title><commentCount>11</commentCount><author>oschina</author><authorid>1</authorid><pubDate>2014-09-12 08:02:11</pubDate><url/><newstype><type>0</type><authoruid2>1</authoruid2></newstype></news><news><id>55200</id><title>【每日一博】基于HTML5实现的Heatmap热图3D应用</title><commentCount>20</commentCount><author>oschina</author><authorid>1</authorid><pubDate>2014-09-12 08:00:56</pubDate><url>http://my.oschina.net/xhload3d/blog/312648</url><newstype><type>3</type><attachment>312648</attachment><authoruid2>1423144</authoruid2></newstype></news>
;; ....
;; </newslist><notice><atmeCount>0</atmeCount><msgCount>0</msgCount><reviewCount>0</reviewCount><newFansCount>0</newFansCount></notice></oschina>

(def osc-news-list
  (str osc-host "/action/api/news_list?catalog=0&pageSize=20&pageIndex="))




;; ;; Details type:
;; See net.oschina.app.bean.News.java
	;; public final static int NEWSTYPE_NEWS = 0x00;//0 新闻
	;; public final static int NEWSTYPE_SOFTWARE = 0x01;//1 软件
	;; public final static int NEWSTYPE_POST = 0x02;//2 帖子
	;; public final static int NEWSTYPE_BLOG = 0x03;//3 博客

; 0: http://www.oschina.net/action/api/news_detail?id=54164
(def osc-news-details
  (str osc-host "/action/api/news_detail?id="))

; 1
(def osc-soft-details
  (str osc-host "/action/api/software_detail?id="))

; 2
(def osc-post-details
  (str osc-host "/action/api/post_detail?id="))


; 3  http://www.oschina.net/action/api/blog_detail?id=312648
(def osc-blog-details
  (str osc-host "/action/api/blog_detail?id="))

(defn get-detail-url
  [type]
  (cond
   (= type "0") osc-news-details
   (= type "1") osc-soft-details
   (= type "2") osc-post-details
   (= type "3") osc-blog-details
   :othewise (do (prn "Unknown newstype:" type)
                 osc-news-details)))


;; catalog
; COMMENT_LIST = URL_API_HOST+"action/api/comment_list";
; BLOGCOMMENT_LIST = URL_API_HOST+"action/api/blogcomment_list";
; http://www.oschina.net/action/api/comment_list?catalog=1&id=54164&pageIndex=0&pageSize=50
; http://www.oschina.net/action/api/blogcomment_list?pageSize=20&id=312648&pageIndex=0

; newstype(0,1)-> catalog(1)
; newstype(3)-> catalog(3), url blogcomment_list
(def osc-comments
  ;; need to append id and catalog
  ;;(str osc-host "/action/api/comment_list?pageIndex=0&pageSize=50&")
  (str osc-host "/action/api/comment_list?"))


(def client-options
  {:timeout 2000
   :follow-redirects false
   :headers {"accept" "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
             "accept-language" "en-US,en;q=0.8,zh;q=0.6"
             "accept-encoding" "gzip"
             "cache-control" "no-cache"
             "https" "1"
             "pragma" "no-cache"
             "user-agent" "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/44.0.2403.89 Safari/537.36"
}})

(defn- get-attachement
  "Get redirect id from node "
  [node]
  (-> (filter #(= :attachment (:tag  %)) (:content node))
      first
      :content
      first))


(defn- get-newstype
  [node]
  (->
   (filter #(= :type (:tag  %)) (:content node))
   first
   :content
   first))

(defn- parse-osc-news
  "Convert news body to a vector of news list."
  [zipbody]
  (->>
   ; whole content
   ; ugly
   (for [m (:content (first zipbody))
         :when (= :newslist (:tag m))
         :let [newslist (:content m)]]
     ; newlist
     (for [news  newslist
           :let [newsvec (:content news)]]
       ; news body
       (for [n newsvec
             :when (or (= (:tag n) :id)
                       (= (:tag n) :title)
                       (= (:tag n) :author)
                       (= (:tag n) :pubDate)
                       (= (:tag n) :commentCount)
                       (= (:tag n) :newstype))]
         (if (= :newstype (:tag n))
           {:newstype (get-newstype n)
            :rid (get-attachement n)}
           {(:tag n) (first (:content n))}))))
   flatten
   identity
   (partition 6 6)
   (map #(reduce merge %))))

(defn get-newslist [page]
  (let [newslist (:body (GET (str osc-news-list page) client-options))
        zipbody (zip-str newslist)]
    (parse-osc-news zipbody)))

(defn- http-request-news
  "Send http request with the input news map, return response body as string"
  [news]
  (prn "Req" news)
  (let [id (or (:rid news) (:id news))
        newstype (or (:newstype news) "0")
        newsdetail-url (str (get-detail-url newstype) id)
        response (:body
                  (if (= "1" newstype) ; type 2, software should be requested with post
                    (POST newsdetail-url (assoc client-options :form-params {:ident id}))
                    (GET newsdetail-url client-options)))]
    response))


(defn get-news-detail
  "Get osc news detail. input should be a map with :id, :newstype keys and optional :rid"
  [news]
  (let [response (http-request-news news)
        zipbody (zip-str  response)]
    (first
     (for [e (->> zipbody
                  first
                  :content
                  first
                  :content)
           :when (= :body (:tag e))]
       (->> e
            :content
            first)))))

(defn- match-type
  "Returns true if n equals numerical or string value of the expected"
  [n expected]
  (let [e (if (string? expected) (read-string expected) (str expected))]
    (some (partial = n) [expected e])))

(defn- get-comments-intern
  "Get osc comments by news id"
  [{:keys [rid newstype id page-index] :as news}]
  (let [; newstype 0 mapped to catalog 1
        catalog       (if (match-type newstype 0) 1 newstype)
        comment-url   (str osc-comments
                           "id=" (or rid id)
                           "&catalog=" catalog
                           "&pageIndex=" (or page-index 0)
                           "&pageSize=20")
        ;; if catalog=3, change url
        comment-url   (if (match-type catalog 3)
                        (cstr/replace comment-url "comment_list" "blogcomment_list")
                        comment-url)
        _             (prn "Req cmts:" comment-url)
        response      (:body (GET comment-url client-options))
        zipbody       (zip-str  response)
        ;; seq of comments. each comments is a vector
        comments-seq
        (doall
         (for [c (->> zipbody first :content)
               :when (= :comments (:tag c))
               :let [cmnts (->> c :content)]]
           (map :content cmnts)))
        ;; seq of map of comments
        comments-maps
        (doall
         (for [comment (flatten comments-seq)
               :when (or (= :content (:tag comment))
                         (= :author (:tag comment))
                         (= :pubDate (:tag comment)))]
           {(:tag comment)
            (first (:content comment))}))]
    (doall
     (->> comments-maps
          (partition 3 3)
          reverse
          (map #(reduce merge %))))))

(def get-comments (wrap-ignore-exception get-comments-intern))

(defn fetch-news
  "Get news at this page, number starts with 1"
  [page]
  (doall
   (for [news (get-newslist (dec page))
         :let [id (:id news)
               body (get-news-detail news)
               all-count (or (:commentCount news) "0")
               max-page (when all-count
                          (inc (int (/ (read-string all-count) 20))))
               comments-by-page-reducer (fn [v page-index]
                                          (concat (get-comments (assoc news :page-index page-index)) v))
               comments (when all-count
                          (reduce comments-by-page-reducer []  (range max-page)))]]
     (assoc news
       :body body
       :comments comments
       :url (str osc-news-details id)))))


(defn convert-newslist
  "Convert newslist to hiccup datascture"
  [newslist]
  (for [news newslist]
    [:div {:class "news"}
     [:h3 {:class "news-title"}
      [:a
       {:href (:url news)}
       (:title news)]
      [:br]
      [:small
       (:url news)]]
     [:div {:class "news-author"}
      (:author news)]
     [:div {:class "news-date"}
      (:pubDate news)]
     [:div {:class "news-body"}
      (or (:body news) (:description news))
      [:span {:id (str "newsid" (:id news))
              :hidden "hidden"}]]
     (if (seq (:comments news))
       [:h4
        [:a
         {:href (:url news)}
         (str (:title news) " - Comments ")]
        [:br]
        [:small
         (:url news)]])
     (for [cmnt (:comments news)]
       [:div {:class "news-comments-list"}
        [:p
         [:div {:class "comment-author"}
          (str "--->" (:author cmnt))
          [:span {:class "comment-date"}
           (str "    " (:pubDate cmnt))]]
         [:div {:class "comment"}
          [:div {:class "comment-content"}
           (:content cmnt)]]]])]))

(defn news-base-html
  "Generate html from newslist"
  [newslist]
  (html
   [:html
    [:head
     [:meta {:charset "UTF-8"}]
     [:link {:href "resources/public/newsbook.css" :rel "stylesheet" :type "text/css"}]
     [:title "OSCHINA NEWS"]]
    [:body (convert-newslist newslist)]]))

(defn -fetch-news-as-html
  "Fetch news and convert to html string. May throw exception
when request refused.
TODO Handle 503 or service rejected"
  ([]
     (-fetch-news-as-html 1))
  ([page]
     (let [_ (GET osc-host client-options)
           newslist (fetch-news page)]
       (news-base-html newslist))))


(defn- get-id-by-osclink
  "Get news id by parsing osc news link.
Sample osc news link:
http://www.oschina.net/news/54237/chanzhieps-2-5"
  [osclink]
  (last (re-find #"news/(\d+)/" osclink)))


(defn is-image-node?
  [node]
  (and node
       (= :img (:tag (zip/node node)))))

(defn download
  "Download a file "
  [url & [filename]]
  (let [output (or filename (get-filename url))]
    (if-not (.exists (io/file output))
      (let [_ (io/make-parents output)
            body (:body
                  (GET url (assoc client-options :as :byte-array)))]
        (when body
          (with-open [w (io/output-stream output)]
            (io/copy body w)))))))

(defn replace-image-node
  [node]
  (let [dir "./images/"
        url (get-in node [:attrs :src])
        filename (str dir (rand-int (Integer/MAX_VALUE)) (get-filename url))]
    (prn "Downloading " url "for" node)
;      (download url filename)
    (assoc-in node [:attrs :src] filename)))



(defn fix-images
  "Get image links from raw html"
  [raw]
  (let [links
        (distinct (map second (re-seq #"<img[^>]+src=\"([^\">]+)\"" raw)))
        rpmap
        (doall
         (for [link links
               :let [dir "./images/"
                     filename (str dir (get-filename link))]]
           (try
             (prn "Downloading " link)
             (download link filename)
             [link filename]
             (catch Exception e
               (.printStackTrace e)
               [link link]))))]
    (try
      (reduce #(cstr/replace % (first %2) (second %2)) raw rpmap)
      (catch Exception e
        (.printStackTrace e)))))


(defn convert-to-epub
  "Convert html file to epub"
  [in]
  (if (calibre?)
    (prn
     "Convertion returns "
     (:exit
      (sh "ebook-convert"
          in
          (cstr/replace in #"\.html$" ".epub")
          "--chapter"
          "//h:h3[re:test(@class, \"news-title\", \"i\")]")))
    (throw (Exception. "Calibre <ebook-convert> command not in path!"))))


(defn fetch-news-as-html
  [& [page]]
  (if-let [page (and page
                     (if (string? page)
                       (read-string page)
                       page))]
    (let [newshtml
          (fix-images
           (if (and page (> page 0))
             (-fetch-news-as-html page)
             (-fetch-news-as-html 1)))
          in (str "oscnews-" page "-"
                  (.format (SimpleDateFormat. "yyyyMMdd_HHmmssSSS")  (Date.))
                  ".html" )]
      (spit in newshtml)
      (convert-to-epub in)))  )



;; Calibre conversion
;;  - toc detection option:
;;      //h:h3[re:test(@class, "news-title", "i")]

(defn -main
  "Start downloading osc news pages"
  [start count]
  (let [[start count] (map read-string [start count])]
    (dotimes [i count]
      (prn "Downloading page" (+ i start))
      (fetch-news-as-html (+ i start))))

  (shutdown-agents))

(comment
  (map :newstype (get-newslist 0))
  (use 'clojure.repl)
  (use 'clojure.pprint)
  (def imgel "<img src=\"http://static.oschina.net/uploads/space/2014/0820/161815_tdgb_554046.jpg\" alt=\"\" />")
  (re-find #"<img[^>]+src=\"([^\">]+)\"" imgel)
  (def newsbody (slurp "sample.html"))
  (def hz (as-hickory (parse newsbody)))
  (def locs (s/select-locs (s/tag :img) hz))
  (def edited (for [loc locs] (zip/edit loc replace-image-node)))
  (binding [*out* (io/writer "edited")] (pprint edited)))
