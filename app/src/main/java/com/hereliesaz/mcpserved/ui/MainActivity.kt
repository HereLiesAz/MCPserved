package com.hereliesaz.mcpserved.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The application's only Activity.
 *
 * Four destinations, no navigation library: the surface is small and fixed, and
 * a graph would be ceremony over four composables. Everything with a lifetime
 * beyond this screen lives in the service and the stores it holds; the Activity
 * is a window onto them and owns nothing itself.
 */
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { McpTheme { Root(vm) } }
    }
}

private enum class Dest(val label: String, val icon: ImageVector) {
    STATUS("Status", Icons.Outlined.PlayArrow),
    PAIR("Pair", Icons.Outlined.Lock),
    GRANTS("Grants", Icons.Outlined.Settings),
    LOG("Log", Icons.Outlined.List)
}

@Composable
private fun Root(vm: MainViewModel) {
    var dest by remember { mutableStateOf(Dest.STATUS) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Dest.entries.forEach { d ->
                    NavigationBarItem(
                        selected = dest == d,
                        onClick = { dest = d },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label) }
                    )
                }
            }
        }
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (dest) {
                Dest.STATUS -> StatusScreen(vm)
                Dest.PAIR -> PairingScreen(vm)
                Dest.GRANTS -> GrantsScreen(vm)
                Dest.LOG -> SessionLogScreen()
            }
        }
    }
}
