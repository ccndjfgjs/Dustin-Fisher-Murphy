package dev.opencode.mobile.data

/**
 * Local, opt-in templates; never merged into or substituted for saved providers. Model IDs and
 * availability can change at the provider, so every field remains editable after choosing one.
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val model: String,
    val extraModels: List<String> = emptyList(),
    val kind: String = "openai",
    val note: String = ""
) {
    fun createProvider(): Provider = Provider(
        name = name,
        kind = kind,
        baseUrl = baseUrl,
        model = model,
        extraModels = extraModels
    )
}

object ProviderCatalog {
    val presets: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "alibaba-studio-intl",
            name = "Alibaba Cloud · Model Studio",
            description = "Singapore · оплата по использованию",
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            model = "qwen3-coder-plus",
            extraModels = listOf("qwen3-coder-next", "qwen3-coder-flash"),
            note = "Ключ Model Studio и оплата по использованию. Регион ключа должен совпадать с адресом. Для другого региона или workspace измените Base URL."
        ),
        ProviderPreset(
            id = "alibaba-studio-us",
            name = "Alibaba Cloud · Model Studio (США)",
            description = "Virginia · оплата по использованию",
            baseUrl = "https://dashscope-us.aliyuncs.com/compatible-mode/v1",
            model = "qwen3-coder-plus",
            extraModels = listOf("qwen3-coder-flash"),
            note = "Ключ Model Studio для региона US (Virginia); запросы оплачиваются по использованию. Регион ключа должен совпадать с адресом."
        ),
        ProviderPreset(
            id = "alibaba-studio-eu",
            name = "Alibaba Cloud · Model Studio (Европа)",
            description = "Frankfurt · нужен ID workspace",
            baseUrl = "https://{WorkspaceId}.eu-central-1.maas.aliyuncs.com/compatible-mode/v1",
            model = "qwen3-coder-next",
            note = "Замените {WorkspaceId} в Base URL на ID вашего workspace во Frankfurt. Нужен ключ этого workspace; запросы оплачиваются по использованию."
        ),
        ProviderPreset(
            id = "alibaba-studio-cn",
            name = "Alibaba Cloud · Model Studio (Китай)",
            description = "Beijing · оплата по использованию",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen3-coder-plus",
            extraModels = listOf("qwen3-coder-next", "qwen3-coder-flash"),
            note = "Ключ Model Studio для региона Beijing; запросы оплачиваются по использованию. Для workspace-specific адреса измените Base URL."
        ),
        ProviderPreset(
            id = "alibaba-coding-intl",
            name = "Alibaba Cloud · Coding Plan",
            description = "Международный · отдельная подписка",
            baseUrl = "https://coding-intl.dashscope.aliyuncs.com/v1",
            model = "qwen3.7-plus",
            extraModels = listOf("qwen3.6-plus", "qwen3.5-plus"),
            note = "Нужен отдельный ключ Coding Plan и подходящая подписка. Проверьте регион, доступность приложения и правила тарифа. Для Qwen3.7/3.6/3.5 Plus используется режим без thinking."
        ),
        ProviderPreset(
            id = "alibaba-coding-cn",
            name = "Alibaba Cloud · Coding Plan (Китай)",
            description = "Китай · отдельная подписка",
            baseUrl = "https://coding.dashscope.aliyuncs.com/v1",
            model = "qwen3.7-plus",
            extraModels = listOf("qwen3.6-plus", "qwen3.5-plus"),
            note = "Нужен ключ Coding Plan (sk-sp-…) и подписка для Китая. Проверьте правила тарифа и поддержку приложения. Для Qwen3.7/3.6/3.5 Plus используется режим без thinking."
        ),
        ProviderPreset(
            id = "deepseek",
            name = "DeepSeek",
            description = "DeepSeek API · программирование и чат",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
            extraModels = listOf("deepseek-v4-pro"),
            note = "Нужен API-ключ DeepSeek. Для совместимости инструментов thinking отключён; внутренние рассуждения не отображаются."
        ),
        ProviderPreset(
            id = "gemini",
            name = "Google Gemini",
            description = "Google AI Studio · OpenAI-совместимый API",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            model = "gemini-3.8-flash",
            note = "Нужен ключ Google AI Studio. Доступ к модели зависит от аккаунта и региона; ID можно изменить."
        ),
        ProviderPreset(
            id = "groq",
            name = "Groq",
            description = "Быстрые модели через Groq API",
            baseUrl = "https://api.groq.com/openai/v1",
            model = "llama-3.3-70b-versatile",
            note = "Нужен ключ Groq. Набор моделей и лимиты зависят от аккаунта."
        ),
        ProviderPreset(
            id = "mistral",
            name = "Mistral AI",
            description = "Mistral API · модели для кода и чата",
            baseUrl = "https://api.mistral.ai/v1",
            model = "mistral-large-latest",
            note = "Нужен API-ключ Mistral. Проверьте доступность и стоимость выбранной модели."
        ),
        ProviderPreset(
            id = "openai",
            name = "OpenAI",
            description = "OpenAI Chat Completions",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o",
            extraModels = listOf("gpt-4o-mini", "gpt-4.1")
        ),
        ProviderPreset(
            id = "anthropic",
            name = "Anthropic",
            description = "Claude · Anthropic Messages API",
            baseUrl = "https://api.anthropic.com",
            kind = "anthropic",
            model = "claude-sonnet-4-5",
            extraModels = listOf("claude-opus-4-1", "claude-haiku-4-5")
        ),
        ProviderPreset(
            id = "openrouter",
            name = "OpenRouter",
            description = "Модели разных провайдеров через один API",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "anthropic/claude-sonnet-4.5",
            extraModels = listOf("openai/gpt-4o", "google/gemini-2.5-pro", "qwen/qwen3-coder")
        ),
        ProviderPreset(
            id = "ollama",
            name = "Ollama (локально)",
            description = "Локальная модель на устройстве или в сети",
            baseUrl = "http://127.0.0.1:11434/v1",
            model = "qwen2.5-coder:7b",
            extraModels = listOf("llama3.1:8b"),
            note = "127.0.0.1 — само устройство. Для Ollama на компьютере из Android Emulator используйте 10.0.2.2. HTTP допустим только в локальной сети и без ключа."
        ),
        ProviderPreset(
            id = "lmstudio",
            name = "LM Studio (LAN)",
            description = "Локальный сервер в сети",
            baseUrl = "http://192.168.1.100:1234/v1",
            model = "local-model",
            note = "Замените адрес и ID модели на значения вашего сервера. HTTP допустим только в локальной сети и без API-ключа."
        )
    )

    fun search(query: String): List<ProviderPreset> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return presets.filter { preset ->
            val searchable = listOf(preset.name, preset.description, preset.baseUrl, preset.model)
                .plus(preset.extraModels).joinToString(" ")
            words.all { searchable.contains(it, ignoreCase = true) }
        }
    }
}
