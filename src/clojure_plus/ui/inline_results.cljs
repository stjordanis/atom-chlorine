(ns clojure-plus.ui.inline-results
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [reagent.core :as r]
            [clojure.walk :as walk]
            [repl-tooling.eval :as eval]
            [clojure-plus.state :refer [state]]))

(defprotocol Taggable
  (obj [this])
  (tag [this]))

(deftype WithTag [obj tag]
  IPrintWithWriter
  (-pr-writer [_ writer opts]
    (-write writer "#")
    (-write writer tag)
    (-write writer " ")
    (-write writer obj))

  Taggable
  (obj [_] obj)
  (tag [_] (str "#" tag " ")))

(defonce ink (atom nil))

(defn new-result [editor row]
  (when-let [InkResult (some-> @ink .-Result)]
    (InkResult. editor #js [row row] #js {:type "block"})))

(defn- ink-tree [header elements block?]
  (when @ink
    (cond-> (-> @ink .-tree (.treeView header (clj->js elements)))
            block? (doto (-> .-classList (.add "line"))))))

(defn set-content [result header elements]
  (let [contents (ink-tree header elements true)]
    (.setContent result contents #js {:error false})))

(defn- to-str [edn]
  (let [tag (when (instance? WithTag edn) (tag edn))
        edn (cond-> edn (instance? WithTag edn) obj)
        start (if-let [more (get edn {:repl-tooling/... nil})]
                (-> edn (dissoc {:repl-tooling/... nil})
                    pr-str (str/replace-first #"\}$" " ...}"))
                (pr-str edn))]

    (-> start
        (str/replace #"\{:repl-tooling/\.\.\. .+?\}" "...")
        (->> (str tag)))))

(declare to-tree)
(defn- as-map [[key val]]
  (let [k-str (str "[" (to-str key))
        v-str (str (to-str val) "]")]
    [:row [[k-str [(to-tree key)]]
           [v-str [(to-tree val)]]]]))

(defn- to-tree [edn]
  (let [txt (to-str edn)
        edn (cond-> edn (instance? WithTag edn) obj)]
    (cond
      (map? edn) [txt (mapv as-map edn)]
      (coll? edn) [txt (mapv to-tree edn)]
      :else [txt])))

(defn- as-obj [unrepl-obj]
  (let [tag? (and unrepl-obj (vector? unrepl-obj) (->> unrepl-obj first (instance? WithTag)))]
    (prn ["TAG?" tag?])
    (if (and tag? (-> unrepl-obj first tag (= "#class ")))
      (let [[f s] unrepl-obj]
        (prn [:OBJ (symbol (str (obj f) "@" s))])
        (symbol (str (obj f) "@" s)))
      unrepl-obj)))

(defn- default-tag [tag data]
  (case (str tag)
    "clojure/var" (->> data (str "#'") symbol)
    ; "unrepl/object" (as-obj data)
    "unrepl.java/class" (WithTag. data "class")
    (WithTag. data tag)))

(defn- leaf [text]
  (doto (.createElement js/document "div")
    (aset "innerText" text)))

(declare to-html)
(defn- html-row [children]
  (let [div (.createElement js/document "div")]
    (doseq [child children]
      (.appendChild div (to-html child)))
    div))

(defn- to-html [[header children]]
  (cond
    (empty? children) (leaf header)
    (= :row header) (html-row children)
    :else (ink-tree header (mapv to-html children) false)))

(defn set-content! [result result-tree]
  (let [contents (to-html result-tree)]
    (.setContent result contents #js {:error false})))

(defn read-result [res]
  (try
    (reader/read-string {:default default-tag} res)
    (catch :default _
      (symbol res))))

(defn put-more-to-end [contents]
  (if-let [get-more (get contents {:repl-tooling/... nil})]
    (-> contents (dissoc {:repl-tooling/... nil}) vec (conj get-more))
    contents))

(defn- get-more [path command wrap?]
  (let [with-res (fn [{:keys [result]}]
                   (let [res (cond->> (put-more-to-end (read-result result))
                                      wrap? (map #(hash-map :contents %)))
                         tagged? (instance? WithTag @path)
                         obj (cond-> @path tagged? obj)
                         ; FIXME: this cond is unnecessary, but it will stay here because
                         ; I want to be sure
                         merged (cond
                                  (vector? obj) (-> obj butlast (concat res) vec)
                                  :else (throw (ex-info "NOT FOUND!!! FIX IT" {})))]
                     (if tagged?
                       (reset! path (WithTag. merged (tag @path)))
                       (reset! path merged))))]
    (some-> @state :repls :clj-eval (eval/evaluate command {} with-res))))

(defn- parse-stack [path stack]
  (if (and (map? stack) (:repl-tooling/... stack))
    {:contents "..." :fn #(get-more path (:repl-tooling/... stack) false)}
    (let [[class method file num] stack]
      (when-not (re-find #"unrepl\.repl\$" (str class))
        {:contents (str "in " (demunge class) " (" method ") at " file ":" num)}))))

(defn- stack-line [idx piece]
  [:div {:key idx}
   (if-let [fun (:fn piece)]
     [:a {:on-click fun} (:contents piece)]
     (:contents piece))])

(defn- error-view [error]
  (when (instance? WithTag (:ex @error))
    (swap! error update :ex obj))
  (let [ex (:ex @error)
        [cause & vias] (:via ex)
        path (r/cursor error [:ex :trace])
        stacks (->> @path
                    (map (partial parse-stack path))
                    (filter identity))]
    [:div
     [:strong {:class "error-description"} (str (:type cause)
                                                ": "
                                                (:message cause))]
     [:div {:class "stacktrace"}
      (map stack-line (range) stacks)]]))

(defn render-error! [result error]
  (let [div (. js/document (createElement "div"))
        res (r/atom (read-result error))]
    (r/render [error-view res] div)
    (.. div -classList (add "error" "clojure-plus"))
    (.setContent result div #js {:error true})))

(defn- expand [parent]
  (if (contains? @parent :children)
    (swap! parent dissoc :children)
    (let [contents (:contents @parent)
          tag? (instance? WithTag contents)
          tag-or-map? (or (map? contents) (and tag? (-> contents obj map?)))
          to-get (cond-> contents (instance? WithTag contents) obj)
          children (mapv (fn [c] {:contents c}) (put-more-to-end to-get))]
      (swap! parent assoc :children children))))

(defn- as-tree-element [edn result]
  (let [tag (when (instance? WithTag edn) (tag edn))
        edn (cond-> edn (instance? WithTag edn) obj)]
    (-> edn
        pr-str
        (str/replace #"\{:repl-tooling/\.\.\. .+?\}" "...")
        (->> (str tag)))))

(defn- coll-views [result]
  [:span
   [:a {:on-click #(expand result)}
    [:span {:class ["icon" (if (contains? @result :children)
                               "icon-chevron-down"
                               "icon-chevron-right")]}]]])

(defn- result-view [parent key]
  (let [result (r/cursor parent key)
        r @result
        contents (:contents r)
        is-more? (and (map? contents) (-> contents keys (= [:repl-tooling/...])))
        keys-of #(if (-> r :children map?)
                   (-> r :children keys)
                   (-> r :children keys count range))]

    [:div {:key (str key)}
     [:div {:style {:display "flex"}}
      (when (and (not is-more?)
                 (or (instance? WithTag contents) (coll? contents)))
        [coll-views result])
      [:span {:style {:white-space "nowrap"}}
       (if is-more?
         [:a {:on-click #(get-more (r/cursor parent [:children])
                                   (:repl-tooling/... contents)
                                   true)}
          (to-str contents)]
         (to-str contents))]]
     (when (contains? r :children)
       [:div {:class "children"}
        (doall (map #(result-view result [:children %]) (keys-of)))])]))

(defn render-result! [result eval-result]
  (let [div (. js/document (createElement "div"))
        res (r/atom [{:contents (read-result eval-result)}])]
    (r/render [result-view res [0]] div)
    (.. div -classList (add "result" "clojure-plus"))
    (.setContent result div #js {:error false})))