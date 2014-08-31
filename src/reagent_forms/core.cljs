(ns reagent-forms.core
  (:require [reagent.core :as reagent :refer [atom]]))

(defn value-of [element]
 (-> element .-target .-value))

(defn mk-save-fn [doc events]
  (fn [id value]
    (swap! doc
      (fn [current-value]
        (reduce #(or (%2 id value %1) %1) (assoc current-value id value) events)))))

;;coerce the input to the appropriate type
(defmulti format-type
  (fn [field-type _] field-type))

(defmethod format-type :numeric
  [_ value]
  (let [parsed (js/parseInt value)]
    (when-not (js/isNaN parsed) parsed)))

(defmethod format-type :default
  [_ value] value)

;;bind the field to the document based on its type
(defmulti bind
  (fn [{:keys [field]} _]
    (if (some #{field} [:text :numeric :password :email :textarea])
      :input-field field)))

(defmethod bind :input-field
  [{:keys [field id]} {:keys [get save!]}]
  {:value (get id)
   :on-change #(save! id (->> % (value-of) (format-type field)))})

(defmethod bind :checkbox
  [{:keys [id]} {:keys [get save! checked]}]
  {:checked @checked
   :on-change #(save! id (swap! checked not))})

(defmethod bind :default [_ _])

(defn set-attrs
  [[type attrs & body] opts & [default-attrs]]
  (into [type (merge default-attrs (bind attrs opts) attrs)] body))

;;initialize the field by binding it to the document and setting default options
(defmulti init-field
  (fn [[_ {:keys [field]}] _]
    (let [field (keyword field)]
      (if (some #{field} [:text :password :email :textarea])
        :input-field field))))

(defmethod init-field :input-field
  [[_ {:keys [field]} :as component] opts]
  (fn []
    (set-attrs component opts {:type field :class "form-control"})))

(defmethod init-field :numeric
  [component opts]
  (fn []
    (set-attrs component opts {:type :text :class "form-control"})))

(defmethod init-field :checkbox
  [[_ {:keys [id field]} :as component] {:keys [get] :as opts}]
  (let [state (atom (get id))]
    (fn []
      (set-attrs component (assoc opts :checked state) {:type field :class "form-control"}))))


(defmethod init-field :alert
  [[type {:keys [id event] :as attrs} & body] {:keys [get save!] :as opts}]
  (let [close-button
        [:button.close
          {:type "button"
           :aria-hidden true
           :on-click #(save! id nil)}
         "X"]]
    (fn []
      (if (and event (event (get id)))
        (into [type (dissoc attrs event)] (cons close-button body))
        (if-let [message (not-empty (get id))]
          [type attrs close-button message])))))

(defmethod init-field :radio
  [[type {:keys [field id value] :as attrs} & body] {:keys [get save!]}]
  (let [state (atom (= value (get id)))]
    (fn []
      (into
        [type
         (merge {:type :radio
                 :checked @state
                 :class "form-control"
                 :on-change
                 #(do
                    (save! id value)
                    (reset! state (= value (get id))))}
                attrs)]
         body))))

(defn- group-item [[type {:keys [key] :as attrs} & body] {:keys [save! multi-select]} selections field id]
  (letfn [(handle-click! []
           (if multi-select
             (do
               (swap! selections update-in [key] not)
               (save! id (->> @selections (filter second) (map first))))
             (let [value (key @selections)]
               (reset! selections {key (if value (not value) true)})
               (save! id (when (key @selections) key)))))]

    (fn []
      [type (merge {:class (if (key @selections) "active")
                    :on-click handle-click!} attrs) body])))

(defn- mk-selections [id selectors {:keys [get multi-select]}]
  (let [value (get id)]
    (reduce
     (fn [m [_ {:keys [key]}]]
       (assoc m key (boolean (some #{key} (if multi-select value [value])))))
     {} selectors)))

(defn selection-group
  [[type {:keys [field id] :as attrs} & selection-items] opts]
  (let [selections (atom (mk-selections id selection-items opts))
        selectors (map (fn [item] [(group-item item opts selections field id)])
                       selection-items)]
    (into [type attrs] selectors)))

(defmethod init-field :single-select
  [field opts]
  (selection-group field opts))

(defmethod init-field :multi-select
  [field opts]
  (selection-group field (assoc opts :multi-select true)))

(defmethod init-field :list
  [[type {:keys [field id] :as attrs} & options] {:keys [get save!]}]
  (let [selection (atom (or
                         (get id)
                         (get-in (first options) [1 :key])))]
    (println "Selection:" @selection)
    (save! id @selection)
    (fn []
      [type (merge attrs {:on-change #(save! id (value-of %))}) options])))

(defn field? [node]
  (and (coll? node)
       (map? (second node))
       (contains? (second node) :field)))

(defn bind-fields [form doc & events]
  (let [opts {:get #(get @doc %) :save! (mk-save-fn doc events)}
        form (clojure.walk/prewalk
               (fn [node]
                 (if (field? node)
                   (let [field (init-field node opts)]
                     (if (fn? field) [field] field))
                   node))
               form)]
    (fn [] form)))
