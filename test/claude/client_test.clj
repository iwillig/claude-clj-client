(ns claude.client-test
  (:require
    [claude.client :as client]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [malli.core :as m]
    [matcher-combinators.matchers :as matchers]
    [matcher-combinators.test :refer [match?]])
  (:import
    (java.time
      ZoneId
      ZonedDateTime)
    (java.time.format
      DateTimeFormatter)))


(def test-token (System/getenv "ANTHROPIC_API_KEY"))
(def mock-token "dummy-token-for-tests")


(defn skip-if-no-token
  [f]
  (if test-token
    (f)
    (println "Skipping API integration tests - ANTHROPIC_API_KEY not set")))


(use-fixtures :once skip-if-no-token)


(deftest test-create-client
  (testing "create client with minimal config"
    (let [client (client/create-client {:api-key mock-token})]
      (is (match? {:api-key mock-token
                   :base-url client/default-base-url
                   :api-version client/default-api-version}
                  client))))

  (testing "create client with custom config"
    (let [custom-url "https://test.api.anthropic.com"
          custom-version "2024-01-01"
          client (client/create-client {:api-key mock-token
                                        :base-url custom-url
                                        :api-version custom-version})]
      (is (match? {:api-key mock-token
                   :base-url custom-url
                   :api-version custom-version}
                  client))))

  (testing "invalid client config throws exception"
    (is (thrown? clojure.lang.ExceptionInfo
          (client/create-client {})))))


(deftest test-schema-validation
  (testing "message schema validation"
    (is (m/validate client/message-schema {:role "user" :content "Hello"}))
    (is (m/validate client/message-schema {:role "assistant" :content "Hi there"}))
    (is (not (m/validate client/message-schema {:role "invalid" :content "Hello"})))
    (is (not (m/validate client/message-schema {:role "user"}))))

  (testing "completions request schema validation"
    (let [valid-msg {:role "user" :content "Hello"}]
      (is (m/validate client/completions-request-schema
                      {:messages [valid-msg]}))
      (is (m/validate client/completions-request-schema
                      {:model "claude-3-opus-20240229"
                       :messages [valid-msg]
                       :max-tokens 100
                       :temperature 0.7
                       :stream false}))
      (is (not (m/validate client/completions-request-schema
                           {:messages "not-a-vector"})))))

  (testing "tool schema validation"
    (let [valid-tool {:name "weather"
                      :description "Get the weather"
                      :input-schema {"location" "string"}}]
      (is (m/validate client/tool-schema valid-tool))
      (is (not (m/validate client/tool-schema {:description "Missing name"})))))

  (testing "message with tool schema validation"
    (let [valid-tool-use {:type "tool_use"
                          :tool_use {:name "calculator"
                                     :input {:x 1 :y 2}}}
          valid-tool-result {:type "tool_result"
                             :tool_result {:content "3"
                                           :tool_use_id "tool-id-123"}}
          valid-text {:type "text" :text "Hello"}]
      (is (m/validate client/message-with-tool-schema
                      {:role "user" :content [valid-text]}))
      (is (m/validate client/message-with-tool-schema
                      {:role "assistant" :content [valid-tool-use]}))
      (is (m/validate client/message-with-tool-schema
                      {:role "user" :content [valid-tool-result]}))
      (is (m/validate client/message-with-tool-schema
                      {:role "assistant" :content [valid-text valid-tool-use valid-tool-result]})))))


(deftest test-serialization
  (testing "write-as-string converts maps to JSON correctly"
    (let [obj {:test "value" :nested {:data 123}}
          json-str (client/write-as-string obj)]
      (is (str/includes? json-str "\"test\":\"value\""))
      (is (str/includes? json-str "\"nested\":{\"data\":123}")))))


(deftest test-api-integration
  (when test-token
    (testing "list models returns expected structure"
      (let [client (client/create-client {:api-key test-token})
            response @(client/list-models client)]
        (is (match? {:models vector?} response))))

    (testing "create message basic functionality"
      (let [client (client/create-client {:api-key test-token})
            request-body {:model "claude-3-haiku-20240307"
                          :max_tokens 50
                          :messages [{:role "user"
                                      :content "What is 2+2? Answer with just the number."}]}
            response @(client/create-message client request-body)
            parsed (client/parse-message-response response)]
        (is (match? {:id string?
                     :content (matchers/embeds [{:text (matchers/embeds "4")}])}
                    parsed))))

    (testing "tool use message functionality"
      (let [get-time-tool {:name "get_current_time"
                           :description "Get the current time in a specified timezone"
                           :input-schema {"timezone" {:type "string"
                                                      :description "Timezone (e.g., 'UTC', 'America/New_York')"}}}

            request-body {:model "claude-3-haiku-20240307"
                          :max_tokens 200
                          :tools [get-time-tool]
                          :messages [{:role "user"
                                      :content "What time is it in UTC? Please use the get_current_time tool."}]}]

        ;; Test tool schema validation
        (is (m/validate client/tool-schema get-time-tool))
        (is (m/validate client/tool-use-request-schema request-body))

        (testing "tool implementation example"
          (let [;; Define the get_current_time tool
                get-time-tool {:name "get_current_time"
                               :description "Get the current time in a specified timezone"
                               :input-schema {"timezone" {:type "string"
                                                          :description "Timezone (e.g., 'UTC', 'America/New_York')"}}}

                ;; Simulate Claude requesting to use the tool
                claude-response {:role "assistant"
                                 :content [{:type "tool_use"
                                            :tool_use {:name "get_current_time"
                                                       :input {:timezone "UTC"}
                                                       :id "tool-use-123"}}]}

                ;; Simulate our tool implementation's response
                current-time (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss z")
                                      (ZonedDateTime/now (ZoneId/of "UTC")))
                tool-result {:role "user"
                             :content [{:type "tool_result"
                                        :tool_result {:tool_use_id "tool-use-123"
                                                      :content current-time}}]}]

            ;; Validate that our messages with tool use are valid
            (is (m/validate client/message-with-tool-schema claude-response))
            (is (m/validate client/message-with-tool-schema tool-result))

            ;; Example of continuing the conversation
            (let [conversation-with-tool-use {:model "claude-3-haiku-20240307"
                                              :max_tokens 100
                                              :tools [get-time-tool]
                                              :messages [{:role "user"
                                                          :content "What time is it in UTC?"}
                                                         claude-response
                                                         tool-result]}]
              (is (m/validate client/tool-use-request-schema conversation-with-tool-use)))))))))
