package dev.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
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
import dev.opencode.mobile.data.ProviderCatalog
import dev.opencode.mobile.data.ProviderPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderCatalogSheet(
    onDismiss: () -> Unit,
    onPreset: (ProviderPreset) -> Unit,
    onCustom: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val presets = remember(query) { ProviderCatalog.search(query) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.imePadding(), containerColor = Color(0xFF1A1E28)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Добавить провайдера", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Выберите шаблон или настройте свой. Сохранённые ключи не изменятся.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().testTag("provider-search"),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Muted) },
                placeholder = { Text("Найти провайдера", color = Muted, fontSize = 13.sp) },
                shape = RoundedCornerShape(14.dp)
            )
            if (presets.isEmpty()) {
                Text("Ничего не найдено. Можно добавить совместимый API вручную.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 22.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 400.dp).testTag("provider-catalog"),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(presets, key = { it.id }) { preset ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                                .background(SurfaceHigh.copy(alpha = 0.55f)).clickable { onPreset(preset) }
                                .padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(preset.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(preset.description, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            TextButton(onClick = onCustom, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Text("  Настроить вручную")
            }
        }
    }
}
