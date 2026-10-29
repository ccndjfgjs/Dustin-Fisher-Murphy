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

## Особенности совместимости

- Официальный [DeepSeek Chat Completions](https://api-docs.deepseek.com/guides/tool_calls/) использует thinking по умолчанию. Агент пока не сохраняет и не передаёт `reasoning_content`, поэтому при запросах **к `api.deepseek.com`** отправляется `thinking.type=disabled`. Это режим без рассуждений; внутренние рассуждения не отображаются.
- На официальных endpoints Alibaba для гибридных `qwen3.7-plus`, `qwen3.6-plus` и `qwen3.5-plus` отправляется `enable_thinking=false`. К другим моделям и провайдерам этот параметр не применяется: в частности, thinking-only модели могут быть несовместимы с текущим циклом агента. Запросы к произвольным совместимым шлюзам не наследуют эти настройки автоматически.
- При прямом подключении к [Google Gemini OpenAI-compatible API](https://ai.google.dev/gemini-api/docs/openai) приложение сохраняет и возвращает [thought signatures](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures) из вызовов инструментов. Это относится только к формату прямого Google API, а не гарантирует работу reasoning моделей через сторонние шлюзы.
- Провайдеры могут изменять model ID, квоты, стоимость и поддержку `temperature`, `max_tokens`, streaming или tool calls. Шаблоны можно отредактировать, и поддержка разных моделей в каталоге не означает проверки каждого сервиса с реальным ключом. Не все API, рекламирующие OpenAI-совместимость, реализуют возможности агента одинаково.
- Остальные источники для шаблонов: [DeepSeek API](https://api-docs.deepseek.com/), [Gemini API](https://ai.google.dev/gemini-api/docs/openai), [Groq API](https://console.groq.com/docs/api-reference), [Mistral API](https://docs.mistral.ai/). Локальные серверы Ollama и LM Studio требуют запущенного совместимого сервера и загруженной модели.

API-ключи хранятся в зашифрованных настройках на устройстве; история сессий, включая сообщения, результаты инструментов и возможные opaque-подписи вызовов Gemini, хранится локально в JSON и **не шифруется приложением**. Выбранному сервису передаются история и результаты инструментов, необходимые для работы агента.
