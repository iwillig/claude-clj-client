# Claude Clojure Client

A Clojure client for the Anthropic Claude API.

## Features

- Complete Claude API coverage
- Support for message creation and streaming
- Support for tool use/function calling
- Content moderation
- Batch processing
- Schema validation using Malli

## Installation

```clojure
;; deps.edn
{:deps {io.github.iwillig/claude-clj-client {:mvn/version "0.1.0"}}}
```

## Basic Usage

```clojure
(require '[claude.client :as claude])

;; Create a client
(def client (claude/create-client {:api-key "your-api-key"}))

;; Send a message
(def response
  @(claude/create-message
    client
    {:model "claude-3-haiku-20240307"
     :max_tokens 100
     :messages [{:role "user" :content "What is the capital of France?"}]}))

;; Parse the response
(def parsed (claude/parse-message-response response))
(println (-> parsed :content first :text))
```

## Tool Use Example

```clojure
(def get-time-tool
  {:name "get_current_time"
   :description "Get the current time in a specified timezone"
   :input-schema {"timezone"
                  {:type "string"
                   :description "Timezone (e.g., 'UTC', 'America/New_York')"}}})

;; Create a message with tool
(def tool-response
  @(claude/create-message-with-tools
    client
    {:model "claude-3-haiku-20240307"
     :max_tokens 200
     :tools [get-time-tool]
     :messages [{:role "user" :content "What time is it in UTC?"}]}))

;; Parse the tool use request
(def parsed-tool-response (claude/parse-message-response tool-response))

;; Implement the tool function and pass the result back
(def tool-result
  {:role "user"
   :content [{:type "tool_result"
              :tool_result {:tool_use_id (-> parsed-tool-response :content first :tool_use :id)
                            :content (get-current-time "UTC")}}]})
```

## Development

1. Use `bb test` to run tests
2. Use `bb lint` to run the clojure linter
3. Use `bb fmt` to fix formatting issues
4. Use `bb clean` to clean the up after the build and tests
