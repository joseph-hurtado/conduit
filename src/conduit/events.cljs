(ns conduit.events
  (:require
   [conduit.db :refer [default-db user->local-store]]
   [re-frame.core :refer [reg-event-db reg-event-fx inject-cofx trim-v after path debug]]
   [day8.re-frame.http-fx]
   [ajax.core :refer [json-request-format json-response-format]]
   [clojure.string :as str]
   [conduit.db :as db]))

;; -- Interceptors --------------------------------------------------------------
;; Every event handler can be "wrapped" in a chain of interceptors. Each of these
;; interceptors can do things "before" and/or "after" the event handler is executed.
;; They are like the "middleware" of web servers, wrapping around the "handler".
;; Interceptors are a useful way of factoring out commonality (across event
;; handlers) and looking after cross-cutting concerns like logging or validation.
;;
;; They are also used to "inject" values into the `coeffects` parameter of
;; an event handler, when that handler needs access to certain resources.
;;
;; Part of the Conduit challenge is to store user in local storage.
;; This interceptor runs `after` an event handler, and it stores the
;; current user into local storage.
;; Later, we include this interceptor into the interceptor chain
;; of all event handlers which modify user  In this way, we ensure that
;; any change to the user is written to local storage.
(def ->local-store (after user->local-store)) ;; @daniel, why do we need to run this after?

;; Each event handler can have its own chain of interceptors.
;; Below we create the interceptor chain shared by all event handlers
;; which manipulate user.
;; A chain of interceptors is a vector.
;; Explanation of `trim-v` is given further below.
;; @daniel, I would like to discuss interceptors in a bit more detail
(def user-interceptors [(path :user)
                        ->local-store                        ;; write user to localstore  (after)
                        (when ^boolean js/goog.DEBUG debug)  ;; look at the js browser console for debug logs
                        trim-v])                             ;; removes first (event id) element from the event vec

;; -- Helpers -----------------------------------------------------------------
;;
(def api-url "https://conduit.productionready.io/api")

(defn uri [& params]
  "Concat any params to api-url separated by /"
  (str/join "/" (concat [api-url] params)))

(defn authorization-header [db]
  "Get user token and format for API authorization"
  [:Authorization (str "Token " (get-in db [:user :token]))])

(defn index-by [key coll]
  "Transform a coll to a map with a given key as a lookup value"
  (into {} (map (juxt key identity) coll)))

;; -- Event Handlers ----------------------------------------------------------
;;
(reg-event-fx    ;; usage: (dispatch [:initialise-db])
 :initialise-db  ;; sets up initial application state

 ;; the interceptor chain (a vector of interceptors)
 [(inject-cofx :local-store-user)]  ;; gets user from localstore, and puts into coeffects arg

 ;; the event handler (function) being registered
 (fn  [{:keys [db local-store-user]} _]               ;; take 2 vals from coeffects. Ignore event vector itself.
   {:db (assoc default-db :user local-store-user)}))  ;; what it returns becomes the new application state

(reg-event-db              ;; usage: (dispatch [:set-active-page :home])
 :set-active-page          ;; triggered when the user clicks on a link
 (fn [db [_ active-page]]  ;; destructure 2nd parameter to obtain active-page
   (assoc db :active-page active-page)))  ;; compute and return the new state

(reg-event-db  ;; usage: (dispatch [:set-active-page :home])
 :set-active-article
 (fn [db [_ active-article]]
   (assoc db :active-article active-article)))

;; -- GET Articles @ /api/articles --------------------------------------------
;;
(reg-event-fx                   ;; usage (dispatch [:get-articles {:limit 10 :tag "tag-name" ...}])
 :get-articles                  ;; triggered when the home page is loaded
 (fn [{:keys [db]} [_ params]]  ;; params = {:limit 10 :tag "tag-name" ...}
   {:http-xhrio {:method          :get
                 :uri             (uri "articles")                          ;; evaluates to "api/articles/"
                 :params          params                                    ;; include params in the request
                 :headers         (authorization-header db)                 ;; get and pass user token obtained during login
                 :response-format (json-response-format {:keywords? true})  ;; json and all keys to keywords
                 :on-success      [:get-articles-success]                   ;; trigger get-articles-success event
                 :on-failure      [:api-request-error :get-articles]}
    :db          (-> db
                     (assoc-in [:loading :articles] true)
                     (assoc-in [:filter :offset] (:offset params))            ;; base on paassed param set a filter
                     (assoc-in [:filter :tag] (:tag params))                  ;; so that we can easily show and hide
                     (assoc-in [:filter :author] (:author params))            ;; appropriate views
                     (assoc-in [:filter :favorites] (:favorited params))
                     (assoc-in [:filter :feed] false))}))                     ;; we need to disable filter by feed every time since it's not supported query param

(reg-event-db
 :get-articles-success
 (fn [db [_ {articles :articles, articles-count :articlesCount}]]
   (-> db
       (assoc-in [:loading :articles] false)
       (assoc :articles-count articles-count)
       (assoc :articles (index-by :slug articles)))))  ;; @daniel, is that the idiomatic way to do it?

;; -- GET Feed Articles @ /api/articles/feed ----------------------------------
;;
(reg-event-fx                   ;; usage (dispatch [:get-feed-articles {:limit 10 :offset 0 ...}])
 :get-feed-articles             ;; triggered when the Your Feed tab is loaded
 (fn [{:keys [db]} [_ params]]  ;; second parameter is not important, therefore _
   {:http-xhrio {:method          :get
                 :uri             (uri "articles" "feed")                   ;; evaluates to "api/articles/feed"
                 :params          params                                    ;; include params in the request
                 :headers         (authorization-header db)                 ;; get and pass user token obtained during login
                 :response-format (json-response-format {:keywords? true})  ;; json and all keys to keywords
                 :on-success      [:get-feed-articles-success]              ;; trigger get-articles-success event
                 :on-failure      [:api-request-error :get-feed-articles]}
    :db          (-> db
                     (assoc-in [:loading :articles] true)
                     (assoc-in [:filter :feed] true))}))                    ;; we need to enable filter by feed every time since it's not supported query param

(reg-event-db
 :get-feed-articles-success
 (fn [db [_ {articles :articles, articles-count :articlesCount}]]
   (-> db
       (assoc-in [:loading :articles] false)
       (assoc :articles-count articles-count)
       (assoc :articles (index-by :slug articles)))))  ;; @daniel, is that the idiomatic way to do it?

;; -- GET Tags @ /api/tags ----------------------------------------------------
;;
(reg-event-fx          ;; usage (dispatch [:get-articles])
 :get-tags             ;; triggered when the home page is loaded
 (fn [{:keys [db]} _]  ;; second parameter is not important, therefore _
   {:db         (assoc-in db [:loading :tags] true)
    :http-xhrio {:method          :get
                 :uri             (uri "tags")                              ;; evaluates to "api/tags/articles/"
                 :response-format (json-response-format {:keywords? true})  ;; json and all keys to keywords
                 :on-success      [:get-tags-success]                       ;; trigger get-tags-success event
                 :on-failure      [:api-request-error :get-tags]}}))        ;; trigger api-request-error with :get-tags param

(reg-event-db
 :get-tags-success
 (fn [db [_ {tags :tags}]]
   (-> db
       (assoc-in [:loading :tags] false)
       (assoc :tags tags))))

;; -- GET Comments @ /api/articles/:slug/comments -----------------------------
;;
(reg-event-fx                   ;; usage (dispatch [:get-article-comments {:slug "article-slug"}])
 :get-article-comments          ;; triggered when the article page is loaded
 (fn [{:keys [db]} [_ params]]  ;; params = {:slug "article-slug"}
   {:db         (assoc-in db [:loading :comments] true)
    :http-xhrio {:method          :get
                 :uri             (uri "articles" (:slug params) "comments")      ;; evaluates to "api/articles/:slug/comments"
                 :headers         (authorization-header db)                   ;; get and pass user token obtained during login
                 :response-format (json-response-format {:keywords? true})        ;; json and all keys to keywords
                 :on-success      [:get-article-comments-success]                 ;; trigger get-articles-success
                 :on-failure      [:api-request-error :get-article-comments]}}))  ;; trigger api-request-error with :get-articles param

(reg-event-db
 :get-article-comments-success
 (fn [db [_ {comments :comments}]]
   (-> db
       (assoc-in [:loading :comments] false)
       (assoc :comments comments))))

;; -- POST Comments @ /api/articles/:slug/comments -----------------------------
;;
(reg-event-fx                   ;; usage (dispatch [:post-article-comments comment])
 :post-article-comments         ;; triggered when a person submits a comment
 (fn [{:keys [db]} [_ params]]  ;; params = {:slug "article-slug" :comment {:body "comment body"} }
   {:db         (assoc-in db [:loading :comments] true)
    :http-xhrio {:method          :post
                 :uri             (uri "articles" (:slug params) "comments")       ;; evaluates to "api/articles/:slug/comments"
                 :headers         (authorization-header db)                        ;; get and pass user token obtained during login
                 :params          (:comment params)
                 :response-format (json-response-format {:keywords? true})         ;; json and all keys to keywords
                 :on-success      [:post-article-comments-success]                 ;; trigger get-articles-success
                 :on-failure      [:api-request-error :post-article-comments]}}))  ;; trigger api-request-error with :get-articles param

(reg-event-db  ;; TODO
 :post-article-comments-success
 (fn [db [_ {comment :comment}]]
   (-> db
       (assoc-in [:loading :comments] false)
       (assoc (:slug comments) :comments comment)))) ;; TODO get the article slug to upate the article

;; -- POST/PUT  Article @ /api/articles(/:slug) ---------------------------------
;;
(reg-event-fx                   ;; usage (dispatch [:post-article-comments comment])
 :upsert-article                ;; triggered when a person submits a comment
 (fn [{:keys [db]} [_ params]]  ;; params = {:slug "article-slug" :comment {:body "comment body"} }
   {:db         (assoc-in db [:loading :article] true)
    :http-xhrio {:method          (if (:slug params) :put :post)            ;; when we get a slug we'll do update (:put) article
                 :uri             (if (:slug params)                        ;; otherwise we'll insert (:post) article
                                    (uri "articles" (:slug params))         ;; Same logic as above but we go wiht different 
                                    (uri "articles"))                       ;; endpoint - one with :slug to update and another 
                 :headers         (authorization-header db)                 ;; without to inster
                 :params          (:article params)
                 :response-format (json-response-format {:keywords? true})  ;; json and all keys to keywords
                 :on-success      [:upsert-article-success]                 ;; trigger get-articles-success
                 :on-failure      [:api-request-error :upsert-article]}}))  ;; trigger api-request-error with :get-articles param

(reg-event-db  ;; TODO
 :upsert-article-success
 (fn [db [_ {article :article}]]
   (-> db
       (assoc-in [:loading :article] false))))

;; -- DELETE  Article @ /api/articles/:slug ---------------------------------
;;
(reg-event-fx                 ;; usage (dispatch [:delete-article slug])
 :delete-article              ;; triggered when a user deletes an article
 (fn [{:keys [db]} [_ slug]]  ;; params = {:slug "article-slug"}
   {:db         (assoc-in db [:loading :article] true)
    :http-xhrio {:method          :delete
                 :uri             (uri "articles" (:slug params))           ;; evaluates to "api/articles/:slug"
                 :headers         (authorization-header db)                 ;; get and pass user token obtained during login
                 :response-format (json-response-format {:keywords? true})  ;; json and all keys to keywords
                 :on-success      [:delete-article-success]                 ;; trigger get-articles-success
                 :on-failure      [:api-request-error :delete-article]}}))  ;; trigger api-request-error with :get-articles param

(reg-event-db  ;; TODO Redirect to home page
 :delete-article-sucsseess
 (fn [db [_ {article :article}]]
   (-> db
       (assoc-in [:loading :article] false))))

;; -- GET Profile @ /api/profiles/:username -----------------------------------
;;
(reg-event-fx       ;; usage (dispatch [:get-user-profile {:profile "profile"}])
 :get-user-profile  ;; triggered when the profile page is loaded
 (fn [{:keys [db]} [_ params]]  ;; params = {:profile "profile"}
   {:db         (assoc-in db [:loading :profile] true)
    :http-xhrio {:method          :get
                 :uri             (uri "profiles" (:profile params))          ;; evaluates to "api/profiles/:profile"
                 :headers         (authorization-header db)                   ;; get and pass user token obtained during login
                 :response-format (json-response-format {:keywords? true})    ;; json and all keys to keywords
                 :on-success      [:get-user-profile-success]                 ;; trigger get-user-profile-success
                 :on-failure      [:api-request-error :get-user-profile]}}))  ;; trigger api-request-error with :get-articles param

(reg-event-db
 :get-user-profile-success
 (fn [db [_ {profile :profile}]]
   (-> db
       (assoc-in [:loading :profile] false)
       (assoc :profile profile))))

;; -- POST Login @ /api/users/login -------------------------------------------
;;
(reg-event-fx                        ;; usage (dispatch [:login user])
 :login                              ;; triggered when a users submits login form
 (fn [{:keys [db]} [_ credentials]]  ;; credentials = {:email ... :password ...}
   {:db         (assoc-in db [:loading :login] true)
    :http-xhrio {:method          :post
                 :uri             (uri "users" "login")                     ;; evaluates to "api/users/login"
                 :params          {:user credentials}                       ;; {:user {:email ... :password ...}}
                 :format          (json-request-format)                     ;; make sure it's json
                 :response-format (json-response-format {:keywords? true})  ;; json and all keys to keywords
                 :on-success      [:login-success]                          ;; trigger login-success
                 :on-failure      [:api-request-error :login]}}))           ;; trigger api-request-error with :credentials param

(reg-event-db
 :login-success
 ;; The standard set of interceptors, defined above, which we
 ;; use for all user-modifying event handlers. Looks after
 ;; writing user to LocalStore.
 ;; NOTE: this chain includes `path` and `trim-v`
 user-interceptors

  ;; The event handler function.
  ;; The "path" interceptor in `user-interceptors` means 1st parameter is the
  ;; value at `:user` path within `db`, rather than the full `db`.
  ;; And, further, it means the event handler returns just the value to be
  ;; put into `:user` path, and not the entire `db`.
  ;; So, a path interceptor makes the event handler act more like clojure's `update-in`
 (fn [user [{props :user}]]
   (merge user props)))

;; -- POST Registration @ /api/users ------------------------------------------
;;
(reg-event-fx                         ;; usage (dispatch [:register-user registration])
 :register-user                       ;; triggered when a users submits registration form
 (fn [{:keys [db]} [_ registration]]  ;; registration = {:username ... :email ... :password ...}
   {:db         (assoc-in db [:loading :register-user] true)
    :http-xhrio {:method          :post
                 :uri             (uri "users")                             ;; evaluates to "api/users/login"
                 :params          {:user registration}                      ;; {:user {:username ... :email ... :password ...}}
                 :format          (json-request-format)                     ;; make sure it's json
                 :response-format (json-response-format {:keywords? true})  ;; json and all keys to keywords
                 :on-success      [:register-user-success]                  ;; trigger login-success
                 :on-failure      [:api-request-error :register-user]}}))   ;; trigger api-request-error with :register-user param

(reg-event-db
 :register-user-success
 ;; The standard set of interceptors, defined above, which we
 ;; use for all user-modifying event handlers. Looks after
 ;; writing user to LocalStore.
 ;; NOTE: this chain includes `path` and `trim-v`
 user-interceptors

  ;; The event handler function.
  ;; The "path" interceptor in `user-interceptors` means 1st parameter is the
  ;; value at `:user` path within `db`, rather than the full `db`.
  ;; And, further, it means the event handler returns just the value to be
  ;; put into `:user` path, and not the entire `db`.
  ;; So, a path interceptor makes the event handler act more like clojure's `update-in`
 (fn [user [{props :user}]]
   (merge user props)))

;; -- PUT Update User @ /api/user ---------------------------------------------
;;
(reg-event-fx                         ;; usage (dispatch [:register-user user])
 :update-user                         ;; triggered when a users updates settgins
 (fn [{:keys [db]} [_ user]]          ;; user = {:img ... :username ... :bio ... :email ... :password ...}
   {:db         (assoc-in db [:loading :update-user] true)
    :http-xhrio {:method          :put
                 :uri             (uri "user")                              ;; evaluates to "api/users/login"
                 :params          {:user user}                              ;; {:user {:img ... :username ... :bio ... :email ... :password ...}}
                 :headers         (authorization-header db)                 ;; get and pass user token obtained during login
                 :format          (json-request-format)                     ;; make sure it's json
                 :response-format (json-response-format {:keywords? true})  ;; json and all keys to keywords
                 :on-success      [:update-user-success]                    ;; trigger update-user-success
                 :on-failure      [:api-request-error :update-user]}}))     ;; trigger api-request-error with :update-user param

(reg-event-db
 :update-user-success
 ;; The standard set of interceptors, defined above, which we
 ;; use for all user-modifying event handlers. Looks after
 ;; writing user to LocalStore.
 ;; NOTE: this chain includes `path` and `trim-v`
 user-interceptors

  ;; The event handler function.
  ;; The "path" interceptor in `user-interceptors` means 1st parameter is the
  ;; value at `:user` path within `db`, rather than the full `db`.
  ;; And, further, it means the event handler returns just the value to be
  ;; put into `:user` path, and not the entire `db`.
  ;; So, a path interceptor makes the event handler act more like clojure's `update-in`
 (fn [user [{props :user}]]
   (merge user props)))

;; -- Toggle follow user @ /api/profiles/:username/follow -----------------------
;;
(reg-event-fx                     ;; usage (dispatch [:toggle-follow-user username])
 :toggle-follow-user              ;; triggered when user clicks follow/unfollow button on profile page
 (fn [{:keys [db]} [_ username]]  ;; username = :username
   {:db         (assoc-in db [:loading :toggle-follow-user] true)
    :http-xhrio {:method          (if (get-in db [:profile :following]) :delete :post)  ;; check if we follow if yes DELETE, no POST
                 :uri             (uri "profiles" username "follow")                    ;; evaluates to "api/profiles/:username/follow"
                 :headers         (authorization-header db)                             ;; get and pass user token obtained during login
                 :format          (json-request-format)                                 ;; make sure it's json
                 :response-format (json-response-format {:keywords? true})              ;; json and all keys to keywords
                 :on-success      [:toggle-follow-user-success]                         ;; trigger follow-user-success
                 :on-failure      [:api-request-error :login]}}))                       ;; trigger api-request-error with :username param

(reg-event-db  ;; usage: (dispatch [:toggle-follow-user-success])
 :toggle-follow-user-success
 (fn [db [_ {profile :profile}]]
   (-> db
       (assoc-in [:loading :toggle-follow-user] false)
       (assoc-in [:profile :following] (:following profile)))))

;; -- Toggle favorite article @ /api/articles/:slug/favorite ------------------
;;
(reg-event-fx                 ;; usage (dispatch [:toggle-favorite-article slug])
 :toggle-favorite-article     ;; triggered when user clicks favorite/unfavorite button on profile page
 (fn [{:keys [db]} [_ slug]]  ;; slug = :slug
   {:db         (assoc-in db [:loading :toggle-favorite-article] true)
    :http-xhrio {:method          (if (get-in db [:articles slug :favorited]) :delete :post)  ;; check if article is favorite if yes DELETE, no POST
                 :uri             (uri "articles" slug "favorite")                            ;; evaluates to "api/profiles/:username/follow"
                 :headers         (authorization-header db)                                   ;; get and pass user token obtained during login
                 :format          (json-request-format)                                       ;; make sure it's json
                 :response-format (json-response-format {:keywords? true})                    ;; json and all keys to keywords
                 :on-success      [:toggle-favorite-article-success]                          ;; trigger follow-user-success
                 :on-failure      [:api-request-error :login]}}))                             ;; trigger api-request-error with :username param

(reg-event-db  ;; usage: (dispatch [:toggle-favorite-article-success])
 :toggle-favorite-article-success
 (fn [db [_ {article :article}]]
   (let [slug (:slug article)
         favorited (:favorited article)]
     (-> db
         (assoc-in [:loading :toggle-favorite-article] false)
         (assoc-in [:articles slug :favorited] favorited)
         (assoc-in [:articles slug :favoritesCount] (if favorited
                                                      (:favoritesCount article inc)
                                                      (:favoritesCount article dec)))))))

;; -- Logout ------------------------------------------------------------------
;;
(reg-event-fx  ;; usage (dispatch [:logout])
 :logout
 (fn [{:keys [db]} [_ _]]
   {:db       (dissoc db :user)
    :dispatch [:set-active-page :home]}))

;; -- Error Handler -----------------------------------------------------------
;;
(reg-event-db
 :api-request-error
 (fn [db [_ & event]]
   (let [request (butlast event)
         response (last event)]
     (assoc-in db
               (into [:errors] request)
               (or (get-in response [:response :errors]
                           {:error [(get response :status-text)]}))))))
