package com.classmemory.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.classmemory.app.asr.AsrHotwordPreferences
import com.classmemory.app.asr.SelectedHotwordCourse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HotwordUiState(val enabled: Boolean, val selectedCourse: SelectedHotwordCourse)

@Composable
fun rememberHotwordUiState(): HotwordUiState {
    val context = LocalContext.current
    var state by remember(context) {
        mutableStateOf(HotwordUiState(false, SelectedHotwordCourse.NONE))
    }
    var revision by remember(context) { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("asr_hotword_settings", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "data_structure_enabled" || key == "selected_course_id" || key == "custom_libraries_v1") {
                revision++
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        revision++
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(context, revision) {
        state = withContext(Dispatchers.IO) { readHotwordUiState(context) }
    }
    return state
}

private fun readHotwordUiState(context: Context): HotwordUiState {
    if (!AsrHotwordPreferences.supportsCourseHotwords()) return HotwordUiState(false, SelectedHotwordCourse.NONE)
    val selected = AsrHotwordPreferences.selectedHotwordCourse(context, tolerateUnreadableCustom = true)
    val storedEnabled = AsrHotwordPreferences.isDataStructureEnabled(context)
    val usable = AsrHotwordPreferences.canEnableDataStructureHotwords(context)
    val enabled = storedEnabled && (usable || selected.id.startsWith("custom_"))
    return HotwordUiState(enabled, selected)
}
