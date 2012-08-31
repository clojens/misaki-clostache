(ns misaki.compiler.clostache.core
  (:use
    [misaki.util file date string]
    [misaki.config    :only [*config*]]
    [clostache.parser :only [render]])
  (:require
    [misaki.core    :as msk]
    [misaki.config  :as cnf]
    [misaki.server  :as srv]
    [clojure.string :as str]))

(def POST_ENTRY_MAX 10)

;; ## Private Functions

(defn- parse-option-line
  [line]
  (re-seq #"^;+\s*@(\w+)\s+(.+)$" line))

;; ## Utilities

; =layout-file?
(defn layout-file?
  [file]
  (if-let [layout-dir (:layout-dir *config*)]
    (str-contains? (.getAbsolutePath file) layout-dir)
    false))

; =get-template-option
(defn get-template-option
  [slurped-data]
  (if (string? slurped-data)
    (let [lines  (map str/trim (str/split-lines slurped-data))
          params (remove nil? (map parse-option-line lines))]
      (into {} (for [[[_ k v]] params] [(keyword k) v])))
    {}))

; =remove-option-lines
(defn remove-option-lines
  [slurped-data]
  (let [lines  (map str/trim (str/split-lines slurped-data))]
    (str/join "\n" (remove #(parse-option-line %) lines))))

; =load-layout
(defn load-layout
  [layout-name]
  (slurp (path (:layout-dir *config*) (str layout-name ".html"))))

; =get-templates
(defn get-templates
  [slurped-data]
  (letfn [(split [s] ((juxt remove-option-lines get-template-option) s))]
    (take-while
      (comp not nil?)
      (iterate (fn [[_ tmpl-option]]
                 (if-let [layout-name (:layout tmpl-option)]
                   (split (load-layout layout-name))))
               (split slurped-data)))))

; =render-template
(defn render-template [file base-data & {:keys [allow-layout?]
                                    :or   {allow-layout? true}}]
  (let [tmpls (get-templates (slurp file))
        htmls (map first tmpls)
        data  (merge base-data (reduce merge (reverse (map second tmpls))))]

    (if allow-layout?
      (reduce
        (fn [result-html tmpl-html]
          (if tmpl-html
            (render tmpl-html (merge data {:content result-html}))
            result-html))
        (render (first htmls) data)
        (rest htmls))
      (render (first htmls) data))))

; =get-post-data
(defn get-post-data
  []
  (map #(let [date (cnf/get-date-from-file %)]
          (assoc (-> % slurp get-template-option)
                 :date (date->string date)
                 :date-xml-schema (date->xml-schema date)
                 :content (render-template % (:site *config*) :allow-layout? false)
                 :url (cnf/make-output-url %)))
       (msk/get-post-files :sort? true)))

;; ## Plugin Definitions

(defn -extension
  []
  (list :htm :html :xml))

(defn -config
  [{:keys [template-dir] :as config}]
  (assoc config
         :layout-dir (path template-dir (:layout-dir config))
         :post-entry-max (:post-entry-max config POST_ENTRY_MAX)))

(defn -compile [config file]
  (binding [*config* config]
    (if (layout-file? file)
      {:status 'skip :all-compile? true}
      (let [posts (get-post-data)
            date  (now)]
        (render-template
          file
          (merge (:site config)
                 {:date      (date->string date)
                  :date-xml-schema (date->xml-schema date)
                  :posts     (take (:post-entry-max config) posts)
                  :all-posts posts}))))))

(defn -main [& args]
  (apply srv/-main args))
