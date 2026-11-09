package dev.scalexy.esphid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import dev.scalexy.esphid.ui.AppModel
import dev.scalexy.esphid.ui.ControlScreen
import dev.scalexy.esphid.ui.DevicesScreen
import dev.scalexy.esphid.ui.EspTheme
import dev.scalexy.esphid.ui.ManualAddScreen
import dev.scalexy.esphid.ui.OnboardScreen
import dev.scalexy.esphid.ui.P
import dev.scalexy.esphid.ui.PointerScreen
import dev.scalexy.esphid.ui.Route
import dev.scalexy.esphid.ui.SettingsPageScreen
import dev.scalexy.esphid.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { EspTheme { AppRoot(model) } }
    }

    override fun onStart() {
        super.onStart()
        model.onAppStart()
    }

    override fun onStop() {
        model.onAppStop()
        super.onStop()
    }
}

@Composable
fun AppRoot(model: AppModel) {
    val top = model.stack.last()
    BackHandler(enabled = model.stack.size > 1) { model.pop() }

    // The HID connection lives as long as a Control screen is on the stack
    // (settings pushed on top keep it open).
    val controlId = model.stack.filterIsInstance<Route.Control>().lastOrNull()?.id
    LaunchedEffect(controlId) {
        if (controlId == null) model.close()
        else if (model.activeId != controlId) model.device(controlId)?.let { model.open(it) }
    }
    val settingsOpen = model.stack.any { it is Route.Settings || it is Route.SettingsPage }
    LaunchedEffect(settingsOpen) { if (!settingsOpen) model.closeSettings() }

    Box(Modifier.fillMaxSize().background(P.paper)) {
        when (top) {
            Route.Devices -> DevicesScreen(model)
            is Route.Control -> ControlScreen(model, top.id)
            is Route.Onboard -> OnboardScreen(model, top.device)
            is Route.ManualAdd -> ManualAddScreen(model, top.mqtt)
            is Route.Settings -> SettingsScreen(model, top.id)
            is Route.SettingsPage -> SettingsPageScreen(model, top.id, top.page)
            Route.Pointer -> PointerScreen(model)
        }
    }
}
