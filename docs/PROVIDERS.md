# Провайдеры и модели

Приложение поддерживает **OpenAI-совместимый Chat Completions** (`/chat/completions`) и **Anthropic Messages API**. Каталог — локальный набор редактируемых подсказок, не список подключённых аккаунтов и не гарантия доступа. Для добавления: в чате нажмите модель у поля ввода → «Добавить или настроить провайдера» → выберите шаблон, проверьте адрес, модель и укажите свой ключ. Также каталог доступен в настройках. Доступно ручное добавление, в том числе нескольких учётных записей одного сервиса.

Существующие провайдеры, модели, URL и ключи **не заменяются** при обновлении. Новые шаблоны доступны независимо от того, были ли у пользователя ранее сохранённые настройки. После добавления они появляются в быстром выборе модели. В нём можно ввести свой model ID в поиск и выбрать «Использовать …» для активного провайдера; предыдущие выбранные модели сохраняются в списке.

## Alibaba Cloud

| Шаблон | Base URL | Ключ и тарификация |
|---|---|---|
| Model Studio, Singapore | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | Model Studio API key; оплата по использованию |
| Model Studio, US (Virginia) | `https://dashscope-us.aliyuncs.com/compatible-mode/v1` | Model Studio API key для региона US; оплата по использованию |
| Model Studio, Germany (Frankfurt) | `https://{WorkspaceId}.eu-central-1.maas.aliyuncs.com/compatible-mode/v1` | Замените `{WorkspaceId}` на реальный ID workspace и используйте ключ этого workspace; оплата по использованию |
| Model Studio, Beijing | `https://dashscope.aliyuncs.com/compatible-mode/v1` | Model Studio API key для соответствующего региона; оплата по использованию |
| Coding Plan, международный | `https://coding-intl.dashscope.aliyuncs.com/v1` | отдельный ключ и подписка Coding Plan |
| Coding Plan, Китай | `https://coding.dashscope.aliyuncs.com/v1` | ключ Coding Plan (`sk-sp-…`) и отдельная подписка |

У Alibaba есть другие регионы и workspace-specific адреса. Измените Base URL в форме, если ваш ключ выдан для другого endpoint. **Не путайте Model Studio pay-as-you-go и Coding Plan**: они используют разные ключи/адреса и биллинг. Проверьте регион, разрешённые приложения и условия подписки в кабинете Alibaba; наличие шаблона не обещает, что тариф доступен этому клиенту. Выбор Model Studio может повлечь отдельные расходы по использованию.

Документация: [адреса Model Studio](https://help.aliyun.com/en/model-studio/base-url), [Coding Plan](https://help.aliyun.com/en/model-studio/coding-plan), [международный Coding Plan / DeepSeek Harness](https://help.aliyun.com/en/model-studio/deepseek-harness), [режим thinking](https://help.aliyun.com/en/model-studio/deep-thinking).

## Dahl Inference, Space Bunny и AgentRouter

Это **шаблоны для самостоятельного подключения**, а не уже настроенные аккаунты. Адрес и ID моделей можно изменить перед сохранением и позже. Набор моделей и права доступа могут меняться; проверяйте их и условия оплаты в кабинете сервиса.

| Шаблон | Base URL | Примеры моделей и подключение |
|---|---|---|
| Dahl Inference | `https://inference.dahl.global/v1` | `MiniMaxAI/MiniMax-M2.7`, `deepseek-ai/DeepSeek-V4-Flash-0731`, `zai-org/GLM-5.3-Flash`. Создайте ключ и выделите ему токены через Allocate в [кабинете](https://inference.dahl.global/account#profile); сверяйте ID с публичным [списком моделей](https://inference.dahl.global/v1/models). |
| Space Bunny | `https://spacebunnymodel.com/api/v1` | `space-bunny-alpha`; `space-bunny-flash` указан в примере интеграции. Ключ `sb_live_` создаётся в [dashboard](https://spacebunnymodel.com/dashboard); API по документации предоплатный, проверьте баланс и актуальный тариф. |
| AgentRouter · OpenAI | `https://agentrouter.org/v1` | OpenAI Chat Completions: `deepseek-v4-flash`, `glm-5.3`. Получите ключ в [кабинете](https://agentrouter.org/console), проверьте доступность моделей и тарификацию. |
| AgentRouter · Anthropic | `https://agentrouter.org` | Anthropic Messages: `claude-opus-5`, `claude-opus-4-8`. Сервис рекомендует Anthropic для Claude Opus; в этом шаблоне адрес **без** `/v1` — приложение добавит `/v1/messages`. |

Если используются оба шаблона AgentRouter, ключ нужно ввести в настройках каждого отдельно; приложение не копирует ключи между провайдерами. Выбор шаблона не делает сетевых запросов и не перезаписывает существующих провайдеров.

Источники: [Dahl API](https://inference.dahl.global/docs/api/) и [OpenCode setup](https://inference.dahl.global/docs/opencode/); [Space Bunny API key](https://spacebunnymodel.com/get-api-key) и [OpenCode integration](https://spacebunnymodel.com/integrations/opencode); [AgentRouter OpenCode](https://agentrouter.org/docs/opencode.html) и [Cline](https://agentrouter.org/docs/cline.html).

**Ограничение для новых сервисов:** авторизованные запросы к ним не проверялись. Отдельные поля `reasoning_content` и `reasoning` отображаются и сохраняются локально, но не отправляются обратно OpenAI-совместимым API. Некоторые reasoning-модели требуют их в цикле инструментов; кроме того, не каждый шлюз поддерживает инструменты. Поэтому агентский цикл не гарантируется даже при успешном обычном чате. Тарифы и поддержка конкретной модели зависят от сервиса.

## Особенности совместимости

- Официальный [DeepSeek](https://api-docs.deepseek.com/guides/thinking_mode/) по умолчанию работает с `thinking.type=disabled`. По желанию режим включается в чате. DeepSeek требует возвращать `reasoning_content` для инструментов, а приложение не пересылает рассуждения не-Anthropic провайдерам; поэтому в этом режиме **инструменты и их предыдущая история исключаются из запроса**. У `deepseek-v4-pro` средний и низкий уровни отображаются как `high` (провайдер не поддерживает low для этой модели), высокий — `max`.
- На официальных endpoints Alibaba для гибридных `qwen3.7-plus`, `qwen3.6-plus` и `qwen3.5-plus` по умолчанию отправляется `enable_thinking=false`; режим включается в чате. Для Qwen3.7/3.6 передаётся `reasoning_effort`; для Qwen3.5 уровни означают включение без гарантии различной глубины. К произвольным шлюзам эти параметры не применяются.
- При прямом подключении к [Google Gemini OpenAI-compatible API](https://ai.google.dev/gemini-api/docs/openai) приложение сохраняет и возвращает [thought signatures](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures) из вызовов инструментов. Это относится только к формату прямого Google API, а не гарантирует работу reasoning моделей через сторонние шлюзы.
- Для известных Claude через Anthropic Messages низкий/средний/высокий уровень использует `budget_tokens` на старых моделях, adaptive thinking и `output_config.effort` на Claude 4.6+ ([документация](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)). Подписанные и скрытые thinking-блоки сохраняются в исходном порядке и возвращаются только тому же провайдеру, адресу и модели, когда thinking включён. OpenAI o-series всегда рассуждают, могут не раскрывать этот текст и не поддерживают настоящее выключение; «Выкл» оставляет настройку сервера по умолчанию.
- Рассуждения и подписанные блоки хранятся отдельно от ответов в локальной истории сессий; история не шифруется как настройки и может содержать чувствительные данные. Переключатель появляется только для известных сочетаний модели и провайдера; пользовательский ID сам по себе поддержку не подтверждает. Уровни — запрос провайдеру, а не гарантия фактической глубины или стоимости.
- Провайдеры могут изменять model ID, квоты, стоимость и поддержку `temperature`, `max_tokens`, streaming или tool calls. Шаблоны можно отредактировать, и поддержка разных моделей в каталоге не означает проверки каждого сервиса с реальным ключом. Не все API, рекламирующие OpenAI-совместимость, реализуют возможности агента одинаково.
- Остальные источники для шаблонов: [DeepSeek API](https://api-docs.deepseek.com/), [Gemini API](https://ai.google.dev/gemini-api/docs/openai), [Groq API](https://console.groq.com/docs/api-reference), [Mistral API](https://docs.mistral.ai/). Локальные серверы Ollama и LM Studio требуют запущенного совместимого сервера и загруженной модели.

API-ключи хранятся в зашифрованных настройках на устройстве; история сессий, включая сообщения, результаты инструментов, рассуждения и подписи провайдеров, хранится локально в JSON и **не шифруется приложением**. Выбранному сервису передаются история и результаты инструментов, необходимые для работы агента.
