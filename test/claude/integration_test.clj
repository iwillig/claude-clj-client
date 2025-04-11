(ns claude.integration-test
  (:require
    [claude.client :as client]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [jsonista.core :as json]
    [manifold.stream :as s]
    [matcher-combinators.matchers :as matchers]
    [matcher-combinators.test :refer [match?]])
  (:import
    (java.time
      ZoneId
      ZonedDateTime)
    (java.time.format
      DateTimeFormatter)))


(def test-token (System/getenv "ANTHROPIC_API_KEY"))


(defn skip-if-no-token
  [f]
  (if test-token
    (f)
    (println "Skipping API integration tests - ANTHROPIC_API_KEY not set")))


(use-fixtures :once skip-if-no-token)


(deftest test-time-tool-integration
  (when test-token
    (testing "complete tool integration flow with get_current_time"
      (let [client (client/create-client {:api-key test-token})

            ;; Define the get_current_time tool
            get-time-tool {:name "get_current_time"
                           :description "Get the current time in a specified timezone"
                           :input-schema {"timezone" {:type "string"
                                                      :description "Timezone (e.g., 'UTC', 'America/New_York')"}}}

            ;; Initial user request
            request-body {:model "claude-3-haiku-20240307"
                          :max_tokens 200
                          :tools [get-time-tool]
                          :messages [{:role "user"
                                      :content "What time is it in America/Los_Angeles? Please use the get_current_time tool."}]}

            ;; Send the initial request
            initial-response @(client/create-message-with-tools client request-body)
            parsed-response (client/parse-message-response initial-response)]

        ;; Verify Claude requested to use our tool
        (is (match? {:role "assistant"
                     :content (matchers/embeds [])} parsed-response))

        (let [tool-use-content (first (filter #(= "tool_use" (:type %)) (:content parsed-response)))]
          (is (match? {:type "tool_use"
                       :tool_use {:name "get_current_time"
                                  :input (matchers/embeds {})
                                  :id string?}}
                      tool-use-content))

          ;; Extract the tool use request
          (let [tool-use-id (get-in tool-use-content [:tool_use :id])
                timezone (get-in tool-use-content [:tool_use :input :timezone])

                ;; Execute the tool
                current-time (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss z")
                                      (ZonedDateTime/now (ZoneId/of timezone)))

                ;; Create tool result response
                tool-result {:role "user"
                             :content [{:type "tool_result"
                                        :tool_result {:tool_use_id tool-use-id
                                                      :content current-time}}]}

                ;; Continue conversation with tool result
                follow-up-request {:model "claude-3-haiku-20240307"
                                   :max_tokens 200
                                   :tools [get-time-tool]
                                   :messages [{:role "user"
                                               :content "What time is it in America/Los_Angeles? Please use the get_current_time tool."}
                                              {:role "assistant"
                                               :content (:content parsed-response)}
                                              tool-result]}

                ;; Get Claude's final response
                final-response @(client/create-message-with-tools client follow-up-request)
                final-parsed (client/parse-message-response final-response)]

            ;; Verify Claude's final response includes the time
            (is (match? {:role "assistant"
                         :content (matchers/embeds [])}
                        final-parsed))

            (let [text-content (first (filter #(= "text" (:type %)) (:content final-parsed)))]
              (is (match? {:type "text"
                           :text string?}
                          text-content))
              (is (str/includes? (:text text-content) "time"))
              (is (str/includes? (:text text-content) "Los Angeles")))))))

    (testing "streaming tool integration"
      (let [client (client/create-client {:api-key test-token})

            ;; Define the get_current_time tool
            get-time-tool {:name "get_current_time"
                           :description "Get the current time in a specified timezone"
                           :input-schema {"timezone" {:type "string"
                                                      :description "Timezone (e.g., 'UTC', 'America/New_York')"}}}

            ;; Initial streaming request
            request-body {:model "claude-3-haiku-20240307"
                          :max_tokens 200
                          :tools [get-time-tool]
                          :messages [{:role "user"
                                      :content "What time is it in UTC? Please use the get_current_time tool."}]}

            ;; Start the stream
            stream (client/create-message-with-tools-stream client request-body)
            response-stream (:body stream)

            ;; Read the first event from stream
            first-chunk (str/trim @(s/try-take! response-stream 5000))
            first-event (when (str/starts-with? first-chunk "data: ")
                          (-> (subs first-chunk 6)
                              (json/read-value client/json-mapper)))]

        ;; Verify we got valid streamed data
        (is (some? first-event))

        ;; We'll just verify the stream works, not process all events
        (s/close! response-stream)))))
