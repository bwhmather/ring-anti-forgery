(ns ring.middleware.anti-forgery
  "Ring middleware to prevent CSRF attacks with an anti-forgery token."
  (:require [crypto.random :as random]))

(def ^:dynamic
  ^{:doc "Binding that stores a anti-forgery token that must be included
          in POST forms if the handler is wrapped in wrap-anti-forgery."}
  *anti-forgery-token*)

(defn default-get-session-token [request]
  (get-in request [:session "__anti-forgery-token"]))

(defn default-assoc-session-token [response request token]
  (if (or (not (= token (get-in request [:session "__anti-forgery-token"])))
          (contains? response :session))
    (-> response
      (assoc :session (or (:session response)
                          (:session request)))
      (assoc-in [:session "__anti-forgery-token"] token))
    response))

(defn default-get-request-token [request]
  (or (get (:form-params request) "__anti-forgery-token")
      (get (:multipart-params request) "__anti-forgery-token")
      (get (:headers request)"x-anti-forgery-token")))

(defn- secure-eql? [^String a ^String b]
  (if (and a b (= (.length a) (.length b)))
    (zero? (reduce bit-or
                   (map bit-xor (.getBytes a) (.getBytes b))))
    false))

(defn- valid-request? [request-token session-token]
  (and request-token
       session-token
       (secure-eql? request-token session-token)))

(defn- access-denied [body]
  {:status 403
   :headers {"Content-Type" "text/html"}
   :body body})

(defn invalid-csrf-token [request]
  (access-denied "<h1>Invalid anti-forgery token</h1>"))

(defn- post-request? [request]
  (= :post (:request-method request)))

(defn wrap-anti-forgery
  "Middleware that prevents CSRF attacks. Any POST request to this handler must
  contain a '__anti-forgery-token' parameter equal to the last value of the
  *anti-request-forgery* var. If the token is missing or incorrect, an access-
  denied response is returned."
  [handler & {:keys [on-potential-csrf-attack
                     generate-token-fn
                     get-session-token
                     assoc-session-token
                     get-request-token]
              :or {on-potential-csrf-attack invalid-csrf-token
                   generate-token-fn #(random/base64 60)
                   get-session-token default-get-session-token
                   assoc-session-token default-assoc-session-token
                   get-request-token default-get-request-token}}]
  (fn [request]
    (let [session-token (or (get-session-token request)
                            (generate-token-fn))
          request-token (get-request-token request)]
      (or (and (post-request? request)
               (not (valid-request? request-token session-token))
               (on-potential-csrf-attack request))
          (if-let [response (binding [*anti-forgery-token* session-token]
                              (handler request))]
            (assoc-session-token response request session-token))))))
