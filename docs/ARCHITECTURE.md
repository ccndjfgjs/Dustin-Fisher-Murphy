# Архитектура OpenCode Mobile

## Слои

```
UI (Compose)  ──  AppViewModel  ──  AgentEngine  ──  AiClient (OpenAI / Anthropic)
                                        │
                                        ├── Tools (реестр + JSON-схемы)
                                        ├── Shell (su / sh, таймауты, отмена)
                                        └── SessionStore / Prefs (JSON на диске)
```

### `agent/AgentEngine`
- Собирает системный промпт (окружение, root, правила).
- Прогоняет цикл: запрос к модели → вызовы инструментов → результаты → следующий запрос.
- Ограничивает историю (последние ~40 сообщений, обрезка на границе `user`), чтобы не переполнять контекст.
- Отдаёт события через `Listener`: статус, дельты стриминга, начало/конец инструмента, todo, запрос подтверждения.

### `ai/AiClient`
- `OpenAiCompatibleClient` — `/chat/completions` с `tools`, поддержка SSE и обычного JSON.
- `AnthropicClient` — `/v1/messages`, `system` отдельным полем, tool_use / tool_result блоки.
- Оба умеют `cancel()` — используется кнопкой «стоп».

### `tools/Tools`
Единый реестр: имя, описание, JSON-схема параметров, реализация.
Схемы автоматически конвертируются в формат OpenAI (`function.parameters`) или Anthropic (`input_schema`).
Инструменты только для чтения помечены в `Tools.readOnly` — они не требуют подтверждения.

Чтобы добавить инструмент:
1. Добавь `ToolSpec` в `Tools.specs`.
2. Добавь ветку в `Tools.execute`.
3. (необязательно) добавь имя в `readOnly`.

### `shell/Shell`
- Определение root: `su -c id` → `RootStatus(available, version, manager, selinux)`.
- `run(command, cwd, useRoot, timeoutSec, onChunk)` — `ProcessBuilder`, объединение stdout/stderr,
  лимит 400k символов, потоковый вывод, отмена по таймауту или прерыванию потока.
- `readText` / `writeText` — сначала обычный доступ, затем fallback через root (temp + `cp`).

### `data`
- `Prefs` — настройки в SharedPreferences (JSON), отдаются как `StateFlow`.
- `SessionStore` — одна сессия = один JSON в `filesDir/sessions`.
- `ChatMessage` хранит вызовы инструментов, чтобы история корректно конвертировалась в API.

## Потоки и отмена
`AgentEngine.run` вызывается из `viewModelScope` на Main; блокирующие операции уходят в `Dispatchers.IO`
(сеть — OkHttp, команды — `runInterruptible`). Поэтому UI не блокируется, а `stop()` отменяет и HTTP-запрос,
и выполняемую команду.

## Безопасность
- Ключи не логируются и не уходят никуда, кроме выбранного провайдера.
- По умолчанию агент спрашивает подтверждение перед записью файлов и командами.
- В режиме без root доступ инструментов ограничен рабочей папкой, `/sdcard` и `/data/local/tmp`.
- Команды ограничены таймаутом, вывод — по размеру.
