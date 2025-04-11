(ns claude.client
  (:require
    [aleph.http :as http]
    [clj-commons.byte-streams :as bs]
    [jsonista.core :as json]
    [malli.core :as m]
    [manifold.deferred :as d]))


(def json-mapper
  (json/object-mapper
    {:decode-key-fn keyword
     :encode-key-fn name}))


(def default-base-url "https://api.anthropic.com")
(def default-api-version "2023-06-01")
(def default-model "claude-3-opus-20240229")
(def default-content-moderation-model "claude-3-haiku-20240307")


(def message-schema
  [:map
   [:role [:enum "user" "assistant"]]
   [:content :string]])


(def client-config-schema
  [:map
   [:api-key :string]
   [:base-url {:optional true} :string]
   [:api-version {:optional true} :string]])


(def completions-request-schema
  [:map
   [:model {:optional true} :string]
   [:messages [:vector message-schema]]
   [:max-tokens {:optional true} :int]
   [:temperature {:optional true} :double]
   [:top-p {:optional true} :double]
   [:top-k {:optional true} :int]
   [:stream {:optional true} :boolean]])


(def tool-schema
  [:map
   [:name :string]
   [:description {:optional true} :string]
   [:input-schema [:map-of :string :any]]])


(def message-with-tool-schema
  [:map
   [:role [:enum "user" "assistant"]]
   [:content [:or :string
              [:vector [:map
                        [:type [:enum "text" "image" "tool_use" "tool_result"]]
                        [:text {:optional true} :string]
                        [:source {:optional true} [:enum "user" "assistant" "tool"]]
                        [:tool_use {:optional true} [:map
                                                     [:name :string]
                                                     [:input [:map-of :string :any]]
                                                     [:id {:optional true} :string]]]
                        [:tool_result {:optional true} [:map
                                                        [:content :string]
                                                        [:tool_use_id :string]]]]]]]])


(def tool-use-request-schema
  [:map
   [:model {:optional true} :string]
   [:messages [:vector message-with-tool-schema]]
   [:tools [:vector tool-schema]]
   [:max-tokens {:optional true} :int]
   [:temperature {:optional true} :double]
   [:top-p {:optional true} :double]
   [:top-k {:optional true} :int]
   [:stream {:optional true} :boolean]])


(defn create-client
  "Creates a new Claude API client with the given configuration.
   Required:
     :api-key - Your Claude API key
   Optional:
     :base-url - API base URL (default: https://api.anthropic.com)
     :api-version - API version (default: 2023-06-01)"
  [config]
  (when-not (m/validate client-config-schema config)
    (throw (ex-info "Invalid client configuration"
                    {:explanation (m/explain client-config-schema config)})))

  (merge {:base-url default-base-url
          :api-version default-api-version}
         config))


(defn- build-headers
  [client]
  {"x-api-key" (:api-key client)
   "anthropic-version" (:api-version client)
   "content-type" "application/json"})


(defn request
  "Send a request to the Claude API"
  [client method endpoint opts]
  (let [url (str (:base-url client) endpoint)
        headers (build-headers client)
        request (merge {:method method
                        :url url
                        :headers headers}
                       opts)]
    (http/request request)))


(defn parse-message-response
  "Parse a message response from the Claude API."
  [response]
  (-> (:body response)
      (bs/to-string)
      (json/read-value json-mapper)))


(defn write-as-string
  [object]
  (json/write-value-as-string object json-mapper))


;; API Endpoint Functions

(defn create-message
  "Create a message via the Claude API.
   This is the low-level function for the /v1/messages endpoint."
  [client request-body]
  (let [body (write-as-string request-body)]
    (request client :post "/v1/messages" {:body body})))


(defn create-message-stream
  "Create a streaming message via the Claude API.
   This is the low-level function for the /v1/messages endpoint with streaming."
  [client request-body]
  (let [body (write-as-string (assoc request-body :stream true))]
    (request client :post "/v1/messages"
             {:body body
              :transform-body-fn identity})))


(defn get-message
  "Get a specific message by ID."
  [client message-id]
  (-> (request client :get (str "/v1/messages/" message-id) {})
      (d/chain parse-message-response)))


(defn list-models
  "List all available models."
  [client]
  (-> (request client :get "/v1/models" {})
      (d/chain parse-message-response)))


(defn delete-message
  "Delete a specific message by ID."
  [client message-id]
  (-> (request client :delete (str "/v1/messages/" message-id) {})
      (d/chain parse-message-response)))


(defn create-completion
  "Create a completion via the Claude API.
  This uses the older /v1/completions endpoint."
  [client request-body]
  (let [body (write-as-string request-body)]
    (-> (request client :post "/v1/completions" {:body body})
        (d/chain parse-message-response))))


(defn create-completion-stream
  "Create a streaming completion via the Claude API.
  This uses the older /v1/completions endpoint with streaming."
  [client request-body]
  (let [body (write-as-string (assoc request-body :stream true))]
    (request client :post "/v1/completions"
             {:body body
              :transform-body-fn identity})))


(defn create-message-with-tools
  "Create a message with tool use via the Claude API.
  This is for the /v1/messages endpoint with tool use enabled."
  [client request-body]
  (when-not (m/validate tool-use-request-schema request-body)
    (throw (ex-info "Invalid tool use request"
                    {:explanation (m/explain tool-use-request-schema request-body)})))
  (let [body (write-as-string request-body)]
    (-> (request client :post "/v1/messages" {:body body})
        (d/chain parse-message-response))))


(defn create-message-with-tools-stream
  "Create a streaming message with tool use via the Claude API.
  This is for the /v1/messages endpoint with tool use enabled and streaming."
  [client request-body]
  (when-not (m/validate tool-use-request-schema request-body)
    (throw (ex-info "Invalid tool use request"
                    {:explanation (m/explain tool-use-request-schema request-body)})))
  (let [body (write-as-string (assoc request-body :stream true))]
    (request client :post "/v1/messages"
             {:body body
              :transform-body-fn identity})))


(defn create-batch-messages
  "Create multiple messages in parallel via the Claude API.
  This uses the /v1/batch/messages endpoint."
  [client request-body]
  (let [body (write-as-string request-body)]
    (-> (request client :post "/v1/batch/messages" {:body body})
        (d/chain parse-message-response))))


(defn get-batch-status
  "Get the status of a batch request by ID."
  [client batch-id]
  (-> (request client :get (str "/v1/batch/" batch-id) {})
      (d/chain parse-message-response)))


(defn moderate-content
  "Moderate content to check for harmful or violating content.
   Uses the /v1/content_moderation endpoint."
  [client request-body]
  (let [body (write-as-string (merge {:model default-content-moderation-model} request-body))]
    (-> (request client :post "/v1/content_moderation" {:body body})
        (d/chain parse-message-response))))
