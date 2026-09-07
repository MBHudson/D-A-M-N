package com.damn.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NavRail() {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        header = {
            Icon(Icons.Default.Menu, contentDescription = null, modifier = Modifier.padding(vertical = 12.dp))
        }
    ) {
        NavigationRailItem(
            selected = true,
            onClick = { },
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text("Home") }
        )
        NavigationRailItem(
            selected = false,
            onClick = { },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("Settings") }
        )
        NavigationRailItem(
            selected = false,
            onClick = { },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            label = { Text("Logs") }
        )
    }
}

@Composable
fun LogTerminal(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black,
        shape = RoundedCornerShape(8.dp)
    ) {
        LazyColumn(contentPadding = PaddingValues(8.dp)) {
            items(50) { i ->
                Text(
                    text = "[LOG] Event $i: Connection established on port ${8080 + i}...",
                    color = Color.Green,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun ControlCard(title: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text("Start") }
                OutlinedButton(onClick = {}) { Text("Stop") }
            }
        }
    }
}

@Preview(widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
fun OptionA_TwoColumnGrid() {
    MaterialTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            NavRail()
            Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Text("Controls & Configuration", style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    ControlCard("Port Controls")
                    ControlCard("Target URLs")
                    ControlCard("Network Settings")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1.5f).fillMaxHeight()) {
                    Text("Live Log Stream", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    LogTerminal(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Preview(widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
fun OptionB_SupportingPane() {
    MaterialTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            NavRail()
            Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Main Content
                Column(modifier = Modifier.weight(2f).fillMaxHeight()) {
                    Text("Server Status", style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Card(modifier = Modifier.weight(1f)) {
                            Box(modifier = Modifier.height(200.dp).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                Text("SYSTEM ONLINE", style = MaterialTheme.typography.displaySmall, color = Color(0xFF4CAF50))
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ControlCard("Quick Toggles")
                            ControlCard("Performance")
                        }
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                // Supporting Pane
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Text("Log Preview", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    LogTerminal(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Preview(widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
fun OptionC_AdaptiveCards() {
    MaterialTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            NavRail()
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("DAMN Dashboard", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ControlCard("Server", modifier = Modifier.weight(1f))
                    ControlCard("Client", modifier = Modifier.weight(1f))
                    ControlCard("Proxy", modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(16.dp))
                LogTerminal(modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
