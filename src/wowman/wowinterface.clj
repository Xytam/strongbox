(ns wowman.wowinterface
  (:require
   [me.raynes.fs :as fs]
   [slugify.core :refer [slugify]]
   [wowman
    [core :as core]
    [logging]
    [utils :as utils]
    [http :as http]]
   [net.cgrand.enlive-html :as html]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [java-time]
   [java-time.format]
   ))

(comment
  "wowinterface.com requires browing addons by category rather than alphabetically or by recently updated. 
   An RSS file is available to scrape the recently updated.")

(defn download-category-list
  []
  (http/download "https://www.wowinterface.com/downloads/cat23.html"))

(defn parse-category-list
  []
  (let [snippet (html/html-snippet (download-category-list))
        cat-list (-> snippet (html/select [:div#colleft :div.subcats :div.subtitle :a]))

        final-url (fn [href]
                    "converts the href that looks like '/downloads/cat19.html' to '/downloads/index.php?cid=19"
                    (let [page (fs/base-name href) ;; cat19.html
                          cat-id (str "index.php?cid=" (clojure.string/replace href #"\D*" "")) ;; index.php?cid=19
                          sort-by "&sb=dec_date" ;; updated date, most recent to least recent
                          another-sort-by "&so=desc" ;; most recent to least recent. must be consistent with `sort-by` prefix
                          pt "&pt=f" ;; nfi but it's mandatory
                          page "&page=1" ;; not necessary, and `1` is default. we'll add it here to avoid a cache miss later
                          ]
                      (str "https://www.wowinterface.com/downloads/", cat-id, sort-by, another-sort-by, pt, page)))

        extractor (fn [cat]
                    {:label (-> cat :content first)
                     :url (-> cat :attrs :href final-url)})
        ]
    (debug (format "%s categories found" (count cat-list)))
    (mapv extractor cat-list)))

(defn format-wowinterface-dt
  "formats a shitty US-style m/d/y date with a shitty 12 hour time component and no timezone into a glorious RFC3399 formatted UTC string"
  [dt]
  (let [dt (java-time/local-date-time "MM-dd-yy hh:mm a" dt) ;; "09-07-18 01:27 PM" => obj with no tz
        ;; no tz info available on site, assume utc
        dt-utc (java-time/zoned-date-time dt "UTC") ;; obj with no tz => utc obj
        fmt (get java-time.format/predefined-formatters "iso-offset-date-time")]
    (java-time/format fmt dt-utc)))

(defn extract-addon-summary
  [snippet]
  (try
    (let [uri "https://www.wowinterface.com/downloads/"
          extract-id #(str "info" (-> % (clojure.string/split #"&.+=") last)) ;; ...?foo=bar&id=24731 => info24731
          extract-updated-date (fn [x]
                                 (let [dmy-hm (-> x (subs 8) clojure.string/trim)] ;; "Updated 09-07-18 01:27 PM " => "09-07-18 01:27 PM"
                                   (format-wowinterface-dt dmy-hm)))
          label (-> snippet (html/select [[:a (html/attr-contains :href "fileinfo")] html/content]) first)]
      {:uri (str uri (-> snippet (html/select [:a]) last :attrs :href extract-id))
       :name (-> label slugify)
       :label label
       ;;:description nil ;; not available in summary
       ;;:category-list [] ;; not available in summary, added by caller
       ;;:created-date nil ;; not available in summary
       :updated-date (-> snippet (html/select [:div.updated html/content]) first extract-updated-date)
       :download-count (-> snippet (html/select [:div.downloads html/content]) first (clojure.string/replace #"\D*" "") Integer.)
       })
    (catch RuntimeException re
      (error re "failed to scrape snippet, excluding from results:" (utils/pprint snippet))
      nil)))

(defn scrape-addon-page
  [category]
  (let [page-content (-> category :url http/download html/html-snippet)
        ;; todo: extract page range, generate paginated urls
        snippet-list (-> page-content (html/select [:#filepage :div.file]))
        extractor (fn [snippet]
                    (assoc (extract-addon-summary snippet) :category-list [(:label category)]))
        ]
    (remove nil? (mapv extractor snippet-list))))

;; todo: this will be moved to core
(defn scrape
  []
  (binding [http/*cache* (core/cache)]
    (mapv scrape-addon-page (parse-category-list))))
