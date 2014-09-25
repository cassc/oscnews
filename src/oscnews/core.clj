(ns oscnews.core
  (:gen-class)
  (:use [hiccup.core]
        [hickory.core :only [parse as-hickory as-hiccup parse-fragment]]
        [hickory.zip :only [hickory-zip hiccup-zip]]
        [hickory.convert :only [hickory-to-hiccup]]
        [clojure.java.shell :only [sh]])
  (:require [clj-http.client :as client]
            [hickory.select :as s]
            [clojure.zip :as zip]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as cstr]))

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
;; uris
(def osc-host
  "http://www.oschina.net")

(def osc-rss
  (str osc-host "/news/rss"))

;; Cookie store
(def cs (clj-http.cookies/cookie-store))

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
                                        ; need to append id and catalog
  (str osc-host "/action/api/comment_list?pageIndex=0&pageSize=50&"))


(def request-headers
  {:accept "application/xml"
   :accept-language "zh-cn"
   :host "www.oschina.net"
   :user-agent "Mozilla/5.0 (X11; Linux i686; rv:30.0) Gecko/20100101 Firefox/30.0 Iceweasel/30.0"
;   :cookie "oscid=hAVeMWBecfqVwnPrEC5fwXtoXjWkDMgEmp%2B6EtdV18KKY22xo%2F8UT8H2m2Feqf6yXw1jGPrKEK%2BH%2BIafyB8aBA6qQNsbOU3O9pjMTEgz21LXGpAZFNnFIjTEXOJ70Zi8"
   :cookie (:cookie-store cs)
   :conn-timeout 5000})


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
   (map #(reduce merge %))
   ))

(defn get-newslist [page]
  (let [newslist (:body (client/get (str osc-news-list page)
                                    request-headers))
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
                    (client/post newsdetail-url (assoc request-headers :form-params {:ident id}))
                    (client/get newsdetail-url request-headers)))]
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

(defn get-comments
  "Get osc comments by news id"
  [{:keys [rid newstype id] :as news}]
  (let [; newstype 0 mapped to catalog 1
        catalog (if (match-type newstype 0) 1 newstype)
        comment-url (str osc-comments
                         "id=" (or rid id)
                         "&catalog=" catalog)
        ; if catalog=3, change url
        comment-url (if (match-type catalog 3)
                      (cstr/replace comment-url "comment_list" "blogcomment_list")
                      comment-url)
        _ (prn "Req cmts:" comment-url)
        response (:body (client/get comment-url request-headers))
        zipbody (zip-str  response)
        ;; seq of comments. each comments is a vector
        comments-seq
        (doall
         (for [c (->> zipbody
                      first
                      :content)
               :when (= :comments (:tag c))
               :let [cmnts (->> c
                                :content)]]
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
          (partition 4 4)
          (map #(reduce merge %))))))

; todo get comments only when there is comments
(defn fetch-news-by-page
  "Get this page of news. page starts from 0"
  [page]
  (doall
   (for [news (get-newslist page)
         :let [id (:id news)
               body (get-news-detail id)
               cmtCount (or (:commentCount news) "0")
               _ (prn "Dl  " cmtCount "for news " id)
               comments (if (> (read-string cmtCount) 0)
                          (try
                            (get-comments news)
                            (catch Exception e
                              (.printStackTrace e)
                              (Thread/sleep (* 10 1000))))
                          (prn (str "No comments for " id)))]]
     (assoc news :body body :comments comments))))

(defn fetch-news
  "Get news at this page, number starts with 1"
  [page]
  (doall
   (for [news (get-newslist (dec page))
         :let [id (:id news)
               body (get-news-detail news)
               cmtCount (or (:commentCount news) "0")
               comments (if (> (read-string cmtCount) 0)
                          (get-comments news)
                          (prn (str "No comments for " id)))]]
     (assoc news
       :body body
       :comments comments
       :url (str osc-news-details id)
       ))))


(defn convert-newslist
  "Convert newslist to hiccup datascture"
  [newslist]
  (for [news newslist]
    [:div {:class "news"}
     [:h3 {:class "news-title"}
      [:a
       {:href (:url news)}
       (:title news)]]
     [:div {:class "news-author"}
      (:author news)]
     [:div {:class "news-date"}
      (:pubDate news)]
     [:div {:class "news-body"}
      (or (:body news) (:description news))
      [:span {:id (str "newsid" (:id news))
              :hidden "hidden"}]]
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
     (let [_ (client/get osc-host request-headers)
           newslist (fetch-news page)]
       (news-base-html newslist))))


(defn- get-id-by-osclink
  "Get news id by parsing osc news link.
Sample osc news link:
http://www.oschina.net/news/54237/chanzhieps-2-5"
  [osclink]
  (last (re-find #"news/(\d+)/" osclink)))

(defn get-rss-list
  "Get rss as a seq of news maps.
Each rss item has the following tags:
(:title :link :category :description :pubDate :guid)
"
  []
  (let [_ (client/get osc-host request-headers)
        resp (client/get osc-rss request-headers)
        _ (if (not= 200 (:status resp))
            (prn (str "Failed to retrieve rss list, error code " (:status resp))))
        rsslist-str (:body resp)
        rsslist
        (->>
         (zip-str rsslist-str)
         first
         :content
         first
         :content)]
    (doall
     (for [item rsslist
           :when (= :item (:tag item))
           :let [cnt
                 (reduce
                  #(assoc % (:tag %2)
                          (first (:content %2)))
                  {}
                  (:content item))]]
       (assoc cnt :id (get-id-by-osclink (:link cnt)))))))




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
                  (client/get url (assoc request-headers :as :byte-array)))]
        (with-open [w (io/output-stream output)]
          (io/copy body w))))))

(defn replace-image-node
  [node]
  (let [dir "./images/"
        url (get-in node [:attrs :src])
        filename (str dir (rand-int (Integer/MAX_VALUE)) (get-filename url))]
    (prn "Downloading " url "for" node)
;      (download url filename)
    (assoc-in node [:attrs :src] filename)))


(defn tree-edit
  "Take a zipper, a function that matches a pattern in the tree,
   and a function that edits the current location in the tree.  Examine the tree
   nodes in depth-first order, determine whether the matcher matches, and if so
   apply the editor."
  [zipper matcher editor]
  (loop [loc zipper]
    (if (zip/end? loc)
      (zip/root loc)
      (if-let [matcher-result (matcher loc)]
        (let [new-loc (zip/edit loc editor)]
          (if (not (= (zip/node new-loc) (zip/node loc)))
            (recur (zip/next new-loc))))
        (recur (zip/next loc))))))


(defn fix-images-fortree
  [news-html]
  (let [z (hiccup-zip
           (as-hiccup
            (parse news-html)))]
    (prn "Fixing images ...")
    (html
     (tree-edit z is-image-node? replace-image-node))))

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
          in (str "oscnews-" page "-" (System/currentTimeMillis) ".html" )]
      (spit in newshtml)
      (convert-to-epub in)))  )



;; Calibre conversion
;;  - toc detection option:
;;      //h:h3[re:test(@class, "news-title", "i")]

(defn -main
  "Start downloading osc news pages"
  [& [page]]
  (let [page (or page 0)]
    (prn "Downloading page" page)
    (fetch-news-as-html page))
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
