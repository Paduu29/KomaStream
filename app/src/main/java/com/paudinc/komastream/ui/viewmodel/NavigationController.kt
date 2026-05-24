package com.paudinc.komastream.ui.viewmodel

import com.paudinc.komastream.ui.navigation.RootTab
import com.paudinc.komastream.ui.navigation.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NavigationController(
    initialStack: List<Screen>,
) {
    private val _navigationStack = MutableStateFlow(initialStack.ifEmpty { listOf(Screen.ProviderPicker) })
    val navigationStack: StateFlow<List<Screen>> = _navigationStack.asStateFlow()

    val screen: Screen
        get() = _navigationStack.value.last()

    fun pushScreen(next: Screen) {
        _navigationStack.value = _navigationStack.value + next
    }

    fun replaceRoot(tab: RootTab) {
        _navigationStack.value = listOf(Screen.Root(tab))
    }

    fun replaceTop(next: Screen) {
        _navigationStack.value = _navigationStack.value.dropLast(1) + next
    }

    fun goBack(): Boolean {
        return if (_navigationStack.value.size > 1) {
            _navigationStack.value = _navigationStack.value.dropLast(1)
            true
        } else {
            false
        }
    }
}
