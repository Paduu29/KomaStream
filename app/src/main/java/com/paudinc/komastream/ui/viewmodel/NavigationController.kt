package com.paudinc.komastream.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paudinc.komastream.ui.navigation.RootTab
import com.paudinc.komastream.ui.navigation.Screen

class NavigationController(
    initialScreen: Screen,
) {
    var navigationStack by mutableStateOf(listOf(initialScreen))
        private set

    val screen: Screen
        get() = navigationStack.last()

    fun pushScreen(next: Screen) {
        navigationStack = navigationStack + next
    }

    fun replaceRoot(tab: RootTab) {
        navigationStack = listOf(Screen.Root(tab))
    }

    fun replaceTop(next: Screen) {
        navigationStack = navigationStack.dropLast(1) + next
    }

    fun goBack(): Boolean {
        return if (navigationStack.size > 1) {
            navigationStack = navigationStack.dropLast(1)
            true
        } else {
            false
        }
    }
}
