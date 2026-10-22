package dev.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun EditorScreen(vm: AppViewModel) {
    val ui = vm.ui
    val path = ui.editorPath ?: ""

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = File(path).name.ifBlank { "Редактор" },
            subtitle = (if (ui.editorDirty) "● не сохранено · " else "") + path
        ) {
            IconBubble(Icons.Default.ArrowBack, onClick = { vm.closeEditor() })
            IconBubble(Icons.Default.Check, tint = if (ui.editorDirty) Accent2 else Success, onClick = { vm.saveEditor() })
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceColor)
        ) {
            BasicTextField(
                value = ui.editorText,
                onValueChange = { vm.updateEditor(it) },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(Accent)
            )
        }
    }
}
