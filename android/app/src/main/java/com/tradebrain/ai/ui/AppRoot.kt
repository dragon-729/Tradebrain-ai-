package com.tradebrain.ai.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tradebrain.ai.data.Graph

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Default.Home),
    Analyze("analyze", "Analyze", Icons.Default.Analytics),
    Library("library", "Library", Icons.Default.MenuBook),
    Assistant("assistant", "AI", Icons.Default.QuestionAnswer),
    Profile("profile", "Profile", Icons.Default.Person)
}

@Composable
fun AppRoot() {
    val loggedIn by Graph.session.loggedIn.collectAsStateWithLifecycle()
    Surface(color = MaterialTheme.colorScheme.background) {
        if (loggedIn) MainScaffold() else AuthScreen()
    }
}

@Composable
private fun MainScaffold() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    fun go(tab: Tab) = nav.navigate(tab.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    Scaffold(bottomBar = {
        NavigationBar {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = entry?.destination?.hierarchy?.any { it.route == t.route } == true,
                    onClick = { go(t) }, icon = { Icon(t.icon, contentDescription = t.label) },
                    label = { Text(t.label) })
            }
        }
    }) { pad ->
        NavHost(nav, startDestination = Tab.Home.route, modifier = Modifier.padding(pad)) {
            composable(Tab.Home.route) { HomeScreen(onGo = { go(it) }) }
            composable(Tab.Analyze.route) { AnalyzeScreen() }
            composable(Tab.Library.route) { LibraryScreen() }
            composable(Tab.Assistant.route) { AssistantScreen() }
            composable(Tab.Profile.route) { ProfileScreen() }
        }
    }
}
