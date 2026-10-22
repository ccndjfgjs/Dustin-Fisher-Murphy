package dev.opencode.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.opencode.mobile.ui.AppRoot
import dev.opencode.mobile.ui.AppViewModel
import dev.opencode.mobile.ui.OpenCodeTheme
import dev.opencode.mobile.ui.Screen

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenCodeTheme {
                BackHandler(enabled = vm.ui.busy) { vm.stop() }
                BackHandler(enabled = !vm.ui.busy && vm.ui.screen == Screen.EDITOR) { vm.closeEditor() }
                AppRoot(vm)
            }
        }
    }
}
