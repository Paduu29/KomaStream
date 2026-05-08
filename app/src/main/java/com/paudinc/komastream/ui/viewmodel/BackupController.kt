package com.paudinc.komastream.ui.viewmodel

import android.net.Uri
import android.util.Log
import com.paudinc.komastream.data.model.BackupOperationType
import com.paudinc.komastream.data.model.BackupOperationUiState
import com.paudinc.komastream.data.repository.BackupFileInteractor
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.LibraryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class BackupController(
    private val scope: CoroutineScope,
    private val libraryStore: LibraryStore,
    private val backupFileInteractor: BackupFileInteractor,
    private val strings: AppStrings,
) {
    private val _operationState = MutableStateFlow<BackupOperationUiState>(BackupOperationUiState.Idle)
    val operationState: StateFlow<BackupOperationUiState> = _operationState.asStateFlow()

    private fun buildEtaSeconds(startedAtMillis: Long, progressPercent: Int): Int? {
        if (progressPercent <= 0) return null
        val elapsedSeconds = ((System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L) / 1000.0)
        if (elapsedSeconds <= 0.5) return null
        val remaining = elapsedSeconds * (100.0 - progressPercent.toDouble()) / progressPercent.toDouble()
        return remaining.roundToInt().coerceAtLeast(0)
    }

    fun exportBackup(uri: Uri) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val startedAt = System.currentTimeMillis()
                    _operationState.value = BackupOperationUiState.InProgress(
                        type = BackupOperationType.EXPORT,
                        progressPercent = 0,
                        stageMessage = strings.backupExporting,
                    )
                    val bytes = libraryStore.exportBackup().toByteArray()
                    backupFileInteractor.exportBackup(uri, bytes) { progress ->
                        _operationState.value = BackupOperationUiState.InProgress(
                            type = BackupOperationType.EXPORT,
                            progressPercent = progress,
                            stageMessage = strings.backupExporting,
                            etaSeconds = buildEtaSeconds(startedAt, progress),
                        )
                    }
                }
                _operationState.value = BackupOperationUiState.Completed(
                    type = BackupOperationType.EXPORT,
                    success = true,
                    message = strings.backupExportSuccess,
                )
            }.onFailure {
                Log.e("KomaStream", "Export failed", it)
                _operationState.value = BackupOperationUiState.Completed(
                    type = BackupOperationType.EXPORT,
                    success = false,
                    message = it.message ?: strings.exportBackupError,
                )
            }
        }
    }

    fun importBackup(
        uri: Uri,
        selectedProviderIdFallback: String,
        onImported: () -> Unit,
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val startedAt = System.currentTimeMillis()
                    _operationState.value = BackupOperationUiState.InProgress(
                        type = BackupOperationType.IMPORT,
                        progressPercent = 0,
                        stageMessage = strings.backupImporting,
                    )
                    val payloadBytes = backupFileInteractor.importBackup(uri) { progress ->
                        val adjusted = (progress.coerceIn(0, 100) * 6) / 10
                        _operationState.value = BackupOperationUiState.InProgress(
                            type = BackupOperationType.IMPORT,
                            progressPercent = adjusted.coerceIn(0, 60),
                            stageMessage = strings.backupReadingFile,
                            etaSeconds = buildEtaSeconds(startedAt, adjusted.coerceAtLeast(1)),
                        )
                    }
                    val restoredPayload = payloadBytes.toString(Charsets.UTF_8)
                    _operationState.value = BackupOperationUiState.InProgress(
                        type = BackupOperationType.IMPORT,
                        progressPercent = 62,
                        stageMessage = strings.backupPreparingRestore,
                        etaSeconds = buildEtaSeconds(startedAt, 62),
                    )
                    libraryStore.importBackup(
                        payload = restoredPayload,
                        selectedProviderIdFallback = selectedProviderIdFallback,
                        onProgress = { progress, stage ->
                            _operationState.value = BackupOperationUiState.InProgress(
                                type = BackupOperationType.IMPORT,
                                progressPercent = progress.coerceIn(62, 100),
                                stageMessage = stage,
                                etaSeconds = buildEtaSeconds(startedAt, progress.coerceIn(1, 100)),
                            )
                        },
                    )
                }
                onImported()
                _operationState.value = BackupOperationUiState.Completed(
                    type = BackupOperationType.IMPORT,
                    success = true,
                    message = strings.backupImportSuccess,
                )
            }.onFailure {
                Log.e("KomaStream", "Import failed", it)
                _operationState.value = BackupOperationUiState.Completed(
                    type = BackupOperationType.IMPORT,
                    success = false,
                    message = it.message ?: strings.backupImportError,
                )
            }
        }
    }

    fun dismissDialog() {
        _operationState.value = BackupOperationUiState.Idle
    }
}
