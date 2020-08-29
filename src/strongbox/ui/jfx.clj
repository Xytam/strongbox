(ns strongbox.ui.jfx
  (:require
   [me.raynes.fs :as fs]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [cljfx
    [api :as fx]]
   ;;[clojure.core.cache :as cache]
   [strongbox
    [logging :as logging]
    [utils :as utils]
    [core :as core]])
  (:import
   [javafx.scene.control TextInputDialog Alert]
   [javafx.stage FileChooser DirectoryChooser]
   [javafx.application Platform]
   [javafx.event ActionEvent]
   [javafx.scene Node]))

(defn file-chooser
  [^ActionEvent event & [opt-map]]
  (let [opt-map (or opt-map {})
        default-open-type :open
        open-type (get opt-map :type default-open-type)
        ;; valid for a menu-item
        window (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getWindow)
        chooser (doto (FileChooser.)
                  (.setTitle "Open File"))]
    (when-let [file-obj @(fx/on-fx-thread
                          (case open-type
                            :save (.showSaveDialog chooser window)
                            (.showOpenDialog chooser window)))]
      file-obj)))

(defn dir-chooser
  [^ActionEvent event]
  (let [;; valid for a menu-item
        window (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getWindow)
        chooser (doto (DirectoryChooser.)
                  (.setTitle "Select Directory"))]
    (when-let [dir @(fx/on-fx-thread
                     (.showDialog chooser window))]
      (str dir))))

(comment "this works, but where does the value go? to an event listener?"
         (defn text-input
           [prompt]
           @(fx/on-fx-thread
             (fx/create-component
              {:fx/type :text-input-dialog
      ;;:prompt-text prompt
               :header-text "header text" ;;prompt
               :showing true
               :content-text "content text"
               :title "title"}))))

(defn text-input
  [event prompt]
  (let [;;window (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getWindow)
        widget (doto (TextInputDialog.)
                 (.setTitle "Title")
                 (.setHeaderText "Header Text")
                 (.setContentText "content text"))
        optional-val @(fx/on-fx-thread
                       (.showAndWait widget))]
    (when (and (.isPresent optional-val)
               (not (empty? (.get optional-val))))
      (.get optional-val))))

(defn alert
  [msg alert-type]
  (let [alert-type-map {:warning javafx.scene.control.Alert$AlertType/WARNING
                        :error javafx.scene.control.Alert$AlertType/ERROR
                        :confirm javafx.scene.control.Alert$AlertType/CONFIRMATION
                        :info javafx.scene.control.Alert$AlertType/INFORMATION}
        alert-type (get alert-type-map alert-type)
        widget (doto (Alert. alert-type)
                 (.setContentText msg))]

    @(fx/on-fx-thread (.showAndWait widget))))

;;


(def INSTALLED-TAB 0)
(def SEARCH-TAB 1)

(defn menu-item
  [label handler & [_]]
  {:fx/type :menu-item
   :text (str label)
   :on-action handler})

(defn build-catalogue-menu
  [selected-catalogue catalogue-addon-list]
  (when catalogue-addon-list
    (let [rb (fn [{:keys [label name]}]
               {:fx/type :radio-menu-item
                :text label
                :selected (= selected-catalogue name)
                :toggle-group {:fx/type fx/ext-get-ref
                               :ref ::catalogue-toggle-group}
                :on-action (fn [_]
                             (core/set-catalogue-location! name)
                             (core/save-settings))})]
      (mapv rb catalogue-addon-list))))

(defn menu
  [label items & [_]]
  {:fx/type :menu
   :text label
   :items items})

(defn async
  ([f]
   (async f []))
  ([f arg-list]
   (future
     (try
       (apply f arg-list)
       (catch RuntimeException re
         (error re "unhandled exception in thread"))))))

;; handlers

(defn async-event-handler
  "wraps `f`, calling it with any given `args` later"
  [f]
  (fn [& args]
    (async f args)))

(defn async-handler
  "same as `async-handler` but calls `f` and ignores `args`"
  [f]
  (fn [& _]
    (f)))

(defn event-handler
  "wraps `f`, calling it with any given `args`.
  useful for debugging, otherwise just use the function directly"
  [f]
  (fn [& args]
    (apply f args)))

(defn donothing
  [[& _]]
  nil)

(defn wow-dir-picker
  [ev]
  (when-let [dir (dir-chooser ev)]
    (when (fs/directory? dir)
      ;; doesn't appear possible to select a non-directory with javafx
      (core/set-addon-dir! dir)
      (core/save-settings))))

;; todo: reconcile this with the on-close-request handler in the stage
(defn exit-handler
  [event]
  (println "hit exit handler")
  ;;(javafx.application.Platform/exit) ;; won't open again
  (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getWindow .close)
  (when-not (core/get-state :in-repl?)
    (System/exit 0)))

(def export-user-catalogue-handler donothing)
(def about-strongbox-dialog donothing)

(defn switch-tab-handler
  [tab-idx]
  (fn [event]
    (let [node ^Node (-> event .getTarget .getParentPopup .getOwnerWindow .getScene .getRoot)
          tabber-obj (first (.lookupAll node "#tabber"))]
      (.select (.getSelectionModel tabber-obj) tab-idx))))

(defn import-addon-handler
  "imports an addon by parsing a URL"
  [event]
  (let [addon-url (text-input event "Enter URL of addon")

        fail-msg "Failed. URL must be:
  * valid
  * originate from github.com
  * addon uses 'releases'
  * latest release has a packaged 'asset'
  * asset must be a .zip file
  * zip file must be structured like an addon"
        failure #(alert fail-msg :error)

        warn-msg "Failed. Addon successfully added to catalogue but could not be installed."
        warning #(alert warn-msg :warning)]
    (when addon-url
      (if-let [result (core/add+install-user-addon! addon-url)]
        (when-not (contains? result :download-url)
          (warning))
        (failure))))
  nil)

(defn import-addon-list-handler
  "prompts user with a file selection dialogue then imports a list of addons from the selected file"
  [event]
  (when-let [;; todo: json file filter
             file-obj (file-chooser event)]
    (core/import-exported-file (-> file-obj .getAbsolutePath str))
    (core/refresh)
    nil))

(defn export-addon-list-handler
  "prompts user with a file selection dialogue then writes the current directory of addons to the selected file"
  [event]
  (when-let [;; todo: json filters
             file-obj (file-chooser event {:type :save})]
    (core/export-installed-addon-list-safely (-> file-obj .getAbsolutePath str))
    nil))

;;

(def separator {:fx/type fx/ext-instance-factory
                :create #(javafx.scene.control.SeparatorMenuItem.)})

(defn menu-bar
  [{:keys [fx/context]}]
  (let [file-menu [(menu-item "New addon directory" (async-event-handler wow-dir-picker) {:key "menu N" :mnemonic "n"})
                   (menu-item "Remove addon directory" (async-handler core/remove-addon-dir!))
                   separator
                   (menu-item "Exit" exit-handler {:key "menu Q" :mnemonic "x"})]

        view-menu [(menu-item "Refresh" (async-handler core/refresh) {:key "F5"})
                   separator
                   (menu-item "Installed" (switch-tab-handler INSTALLED-TAB) {:key "menu I" :mnemonic "i"})
                   (menu-item "Search" (switch-tab-handler SEARCH-TAB) {:key "menu H" :mnemonic "h"})
                   ;; separator
                   ;; todo: build-theme-menu
                   ]

        catalogue-menu (into (build-catalogue-menu
                              (fx/sub-val context get-in [:app-state :cfg :selected-catalogue])
                              (fx/sub-val context get-in [:app-state :cfg :catalogue-location-list]))
                             [separator
                              (menu-item "Refresh user catalogue" (async-handler core/refresh-user-catalogue))])

        addon-menu [(menu-item "Update all" (async-handler core/install-update-all) {:key "menu U" :mnemonic "u"})
                    (menu-item "Re-install all" (async-handler core/re-install-all))]

        impexp-menu [(menu-item "Import addon from Github" (event-handler import-addon-handler))
                     separator
                     (menu-item "Import addon list" (async-event-handler import-addon-list-handler))
                     (menu-item "Export addon list" (async-event-handler export-addon-list-handler))
                     (menu-item "Export Github addon list" (async-handler export-user-catalogue-handler))]

        cache-menu [(menu-item "Clear http cache" (async-handler core/delete-http-cache!))
                    (menu-item "Clear addon zips" (async-handler core/delete-downloaded-addon-zips!))
                    (menu-item "Clear catalogues" (async-handler (juxt core/db-reload-catalogue core/delete-catalogue-files!)))
                    (menu-item "Clear log files" (async-handler core/delete-log-files!))
                    (menu-item "Clear all" (async-handler core/clear-all-temp-files!))
                    separator
                    (menu-item "Delete WowMatrix.dat files" (async-handler core/delete-wowmatrix-dat-files!))
                    (menu-item "Delete .wowman.json files" (async-handler (comp core/refresh core/delete-wowman-json-files!)))
                    (menu-item "Delete .strongbox.json files" (async-handler (comp core/refresh core/delete-strongbox-json-files!)))]

        help-menu [(menu-item "About strongbox" (event-handler about-strongbox-dialog))]]

    {:fx/type fx/ext-let-refs
     :refs {::catalogue-toggle-group {:fx/type :toggle-group}}
     :desc {:fx/type :menu-bar
            :id "main-menu"
            :menus [(menu "File" file-menu {:mnemonic "F"})
                    (menu "View" view-menu {:mnemonic "V"})
                    (menu "Catalogue" catalogue-menu)
                    (menu "Addons" addon-menu {:mnemonic "A"})
                    (menu "Import/Export" impexp-menu {:mnemonic "i"})
                    (menu "Cache" cache-menu)
                    (menu "Help" help-menu)]}}))

(defn installed-addons-menu-bar
  [{:keys [fx/context]}]
  (let [;; temporary
        refresh-button {:fx/type :button
                        :text "Refresh"
                        :on-action (async-handler core/refresh)}
        update-all-button {:fx/type :button
                           :text "Update all"
                           :on-action (async-handler core/install-update-all)}

        addon-dir-map-list (or (fx/sub-val context get-in [:app-state :cfg :addon-dir-list]) [])
        selected-addon-dir (fx/sub-val context get-in [:app-state :cfg :selected-addon-dir])
        selected-game-track (core/get-game-track selected-addon-dir)

        wow-dir-dropdown {:fx/type :combo-box
                          :value selected-addon-dir
                          :on-value-changed (async-event-handler
                                             (fn [new-addon-dir]
                                               (core/set-addon-dir! new-addon-dir)))
                          :items (mapv :addon-dir addon-dir-map-list)}

        game-track-dropdown {:fx/type :combo-box
                             :value (-> selected-game-track (or "") name)
                             :on-value-changed (async-event-handler
                                                (fn [new-game-track]
                                                  (core/set-game-track! (keyword new-game-track))
                                                  (core/refresh)))
                             :items ["retail" "classic"]}

        ;; todo: add upgrade strongbox button
        ]
    {:fx/type :h-box
     :padding 10
     :spacing 10
     :children [refresh-button
                update-all-button
                wow-dir-dropdown
                game-track-dropdown]}))

(defn table-column
  [column-data]
  (let [column-data (if (string? column-data) {:text column-data} column-data)
        column-name (:text column-data)

        default-cvf (fn [row] (get row (keyword column-name)))
        new-cvf (:cell-value-factory column-data)
        final-cvf (if (and (some? new-cvf)
                           (keyword? (:cell-value-factory column-data)))
                    ;; keywords have to be wrapped in a function to be coerced to a javafx callback!
                    #(get % (:cell-value-factory column-data))
                    (or new-cvf default-cvf))
        final-cvf {:cell-value-factory final-cvf}

        default {:fx/type :table-column
                 :visible true
                 :min-width 80}]
    (merge default column-data final-cvf)))

(defn source-to-href-fn
  "if a source for the addon can be derived, return a hyperlink"
  [row]
  (when-let [source (:url row)]
    (let [url (java.net.URL. source)]
      (case (.getHost url)
        "www.curseforge.com" "curseforge"
        "www.wowinterface.com" "wowinterface"
        "github.com" "github"
        "www.tukui.org" (if (= (.getPath url) "/classic-addons.php")
                          "tukui-classic"
                          "tukui")
        "???"))))

(defn installed-addons-table
  [{:keys [fx/context]}]
  (let [row-list (fx/sub-val context get-in [:app-state :installed-addon-list])
        column-list [{:text "source" :min-width 100 :max-width 110 :cell-value-factory source-to-href-fn}
                     {:text "name" :min-width 150 :pref-width 300 :max-width 500 :cell-value-factory :label}
                     {:text "description" :pref-width 700 :cell-value-factory :description}
                     {:text "installed" :max-width 150 :cell-value-factory :installed-version}
                     {:text "available" :max-width 150 :cell-value-factory :version}
                     {:text "WoW" :max-width 100 :cell-value-factory :interface-version}]]
    {:fx/type :table-view
     :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
     :selection-mode :multiple
     :pref-height 999.0
     :columns (mapv table-column column-list)
     :items (or row-list [])}))

(defn notice-logger
  [{:keys [fx/context]}]
  (let [log-message-list (fx/sub-val context :log-message-list)
        log-message-list (reverse log-message-list) ;; nfi how to programmatically change column sort order
        column-list [{:text "level" :max-width 100 :cell-value-factory :level}
                     {:text "message" :pref-width 500 :cell-value-factory :message}]]
    {:fx/type :table-view
     :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
     :columns (mapv table-column column-list)
     :items (or log-message-list [])}))

(defn installed-addons-pane
  [_]
  {:fx/type :v-box
   :children [{:fx/type installed-addons-menu-bar}
              {:fx/type installed-addons-table}]})

(defn search-addons-table
  [{:keys [fx/context]}]
  (let [addon-list (core/db-search (fx/sub-val context get-in [:app-state :search-field-input]))
        column-list [{:text "source" :min-width 100 :max-width 110 :cell-value-factory source-to-href-fn}
                     {:text "name" :min-width 150 :pref-width 300 :max-width 450 :cell-value-factory :label}
                     {:text "description" :pref-width 700 :cell-value-factory :description}
                     {:text "tags" :pref-width 380 :min-width 230 :max-width 450 :cell-value-factory (comp str :tag-list)}
                     {:text "updated" :min-width 85 :max-width 120 :pref-width 100 :cell-value-factory (comp #(utils/safe-subs % 10)  :updated-date)}
                     {:text "downloads" :min-width 100 :max-width 120 :cell-value-factory :download-count}]]
    {:fx/type :table-view
     :column-resize-policy javafx.scene.control.TableView/CONSTRAINED_RESIZE_POLICY
     :selection-mode :multiple
     :pref-height 999.0
     :columns (mapv table-column column-list)
     :items addon-list}))

(defn search-addons-search-field
  [_]
  {:fx/type :h-box
   :padding 10
   :spacing 10
   :children [{:fx/type :text-field
               :prompt-text "search"
               :on-text-changed (fn [v]
                                  (swap! core/state assoc :search-field-input v))}
              {:fx/type :button
               :text "install selected"
               :on-action (async-handler (fn []
                                           ;;(switch-tab INSTALLED-TAB)
                                           ;;(doseq [selected (get-state :selected-search)]
                                           ;;  (some-> selected core/expand-summary-wrapper vector core/-install-update-these)
                                           ;;  (core/load-installed-addons))
                                           ;;(ss/selection! (select-ui :#tbl-search-addons) nil) ;; deselect rows in search table
                                           ;;(core/refresh))
                                           (println "installing ...")))}
              {:fx/type :button
               :text "random"
               :on-action (fn [_]
                            (swap! core/state assoc :search-field-input
                                   (if (nil? (:search-field-input @core/state)) "" nil)))}]})

(defn search-addons-pane
  [_]
  {:fx/type :v-box
   :children [{:fx/type search-addons-search-field}
              {:fx/type search-addons-table}]})

(defn tabber
  [_]
  {:fx/type :tab-pane
   :id "tabber"
   :tabs [{:fx/type :tab
           :text "installed"
           :closable false
           :content {:fx/type installed-addons-pane}}
          {:fx/type :tab
           :text "search"
           :closable false
           :content {:fx/type search-addons-pane}}]})

;;

(defn root
  [_]
  {:fx/type :stage
   :showing true
   :on-close-request (fn [ev]
                       ;; called on ctrl-c
                       ;;(println "got ev" ev)
                       ;;(println (bean ev))
                       (when-not (core/get-state :in-repl?)
                         (System/exit 0)))

   :title "strongbox"
   :width 1024
   :height 768
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :children [{:fx/type menu-bar}
                             {:fx/type :split-pane
                              :orientation :vertical
                              :divider-positions [0.65]
                              :items [{:fx/type tabber}
                                      {:fx/type notice-logger}]}]}}})

(defn init-notice-logger!
  [gui-state]
  (let [gui-logger (fn [log-data]
                     (let [{:keys [timestamp_ msg_ level]} log-data
                           formatted-output-str (force (format "%s - %s" (force timestamp_) (force msg_)))]
                       (swap! gui-state fx/swap-context update-in [:log-message-list] conj {:level level :message formatted-output-str})))]
    (logging/add-appender! :gui gui-logger {:timestamp-opts {:pattern "HH:mm:ss"}})))

(defn start
  []
  (info "starting gui")
  (let [state-template {:app-state nil,
                        :log-message-list []}
        gui-state (atom (fx/create-context state-template)) ;; cache/lru-cache-factory))

        update-gui-state (fn [new-state]
                           (swap! gui-state fx/swap-context assoc :app-state new-state))
        _ (core/state-bind [] update-gui-state)

        renderer (fx/create-renderer
                  :middleware (comp
                               fx/wrap-context-desc
                               (fx/wrap-map-desc (fn [_] {:fx/type root})))

                  ;; magic :(

                  :opts {:fx.opt/type->lifecycle #(or (fx/keyword->lifecycle %)
                                                      ;; For functions in `:fx/type` values, pass
                                                      ;; context from option map to these functions
                                                      (fx/fn->lifecycle-with-context %))})

        ;; on first load, because the catalogue hasn't been loaded
        ;; and because the search-field-input doesn't change,
        ;; and because the search component isn't re-rendered,
        ;; fake a change to get something to appear
        bump-search (fn []
                      (when-not (:search-field-input core/state)
                        (swap! core/state assoc :search-field-input "")))]

    (fx/mount-renderer gui-state renderer)
    (init-notice-logger! gui-state)

    (future
      (core/refresh)
      (bump-search))

    renderer))

(defn stop
  []
  (info "stopping gui") ;; nothing needs to happen ... yet?
  nil)
