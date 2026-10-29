package dev.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.data.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    settings: AppSettings,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String, String) -> Unit,
    onAddProvider: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val search = query.trim()
    val groups = settings.providers.mapNotNull { provider ->
        val models = if (search.isEmpty() || provider.name.contains(search, ignoreCase = true)) provider.models
            else provider.models.filter { it.contains(search, ignoreCase = true) }
        if (models.isEmpty()) null else provider to models
    }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.imePadding(), containerColor = Color(0xFF1A1E28)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Выбор модели", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (busy) "Изменение применится к следующему запросу" else "Выберите модель или введите свой ID в поиск",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().testTag("model-search"),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Muted) },
                placeholder = { Text("Поиск модели или провайдера", fontSize = 13.sp, color = Muted) },
                shape = RoundedCornerShape(14.dp)
            )
            if (groups.isEmpty()) {
                Text("Ничего не найдено. Модель можно добавить в настройках провайдера.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 22.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 400.dp).testTag("model-list"),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    groups.forEach { (provider, models) ->
                        item(key = "provider-${provider.id}") {
                            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(provider.name, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (provider.apiKey.isBlank() && provider.baseUrl.startsWith("https://", ignoreCase = true)) {
                                    Text("нет ключа", color = Muted, fontSize = 10.sp)
                                }
                            }
                        }
                        items(models, key = { "${provider.id}-$it" }) { model ->
                            val selected = provider.id == settings.activeProviderId && model == provider.model
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) Accent.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable { onSelect(provider.id, model) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(model, modifier = Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (selected) Icon(Icons.Default.Check, "Выбрано", tint = Accent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            if (search.isNotEmpty() && search.none { it.isWhitespace() } && settings.activeProvider.models.none { it == search }) {
                TextButton(onClick = { onSelect(settings.activeProvider.id, search) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Использовать «$search» для ${settings.activeProvider.name}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = onAddProvider, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Добавить или настроить провайдера")
            }
        }
    }
}
