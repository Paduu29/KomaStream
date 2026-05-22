package com.paudinc.komastream.ui.viewmodel

import android.net.Uri
import com.paudinc.komastream.data.model.BackupFormat
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
        exportBackup(uri, BackupFormat.JSON)
    }

    fun exportBackup(uri: Uri, format: BackupFormat) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val startedAt = System.currentTimeMillis()
                    val exportStage = when (format) {
                        BackupFormat.JSON -> strings.backupExporting
                        BackupFormat.DATABASE -> strings.exportDatabaseBackup
                    }
                    _operationState.value = BackupOperationUiState.InProgress(
                        type = BackupOperationType.EXPORT,
                        progressPercent = 0,
                        stageMessage = exportStage,
                    )
                    val bytes = when (format) {
                        BackupFormat.JSON -> libraryStore.exportBackup().toByteArray()
                        BackupFormat.DATABASE -> libraryStore.exportDatabaseBackup { progress, stage ->
                            _operationState.value = BackupOperationUiState.InProgress(
                                type = BackupOperationType.EXPORT,
                                progressPercent = progress.coerceIn(0, 90),
                                stageMessage = stage,
                                etaSeconds = buildEtaSeconds(startedAt, progress.coerceAtLeast(1)),
                            )
                        }
                    }
                    backupFileInteractor.exportBackup(uri, bytes) { progress ->
                        _operationState.value = BackupOperationUiState.InProgress(
                            type = BackupOperationType.EXPORT,
                            progressPercent = if (format == BackupFormat.JSON) progress else (90 + (progress / 10)).coerceIn(90, 100),
                            stageMessage = exportStage,
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
        importBackup(uri, BackupFormat.JSON, selectedProviderIdFallback, onImported)
    }

    fun importBackup(
        uri: Uri,
        format: BackupFormat,
        selectedProviderIdFallback: String,
        onImported: () -> Unit,
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val startedAt = System.currentTimeMillis()
                    val importStage = when (format) {
                        BackupFormat.JSON -> strings.backupImporting
                        BackupFormat.DATABASE -> strings.importDatabaseBackup
                    }
                    _operationState.value = BackupOperationUiState.InProgress(
                        type = BackupOperationType.IMPORT,
                        progressPercent = 0,
                        stageMessage = importStage,
                    )
                    val payloadBytes = backupFileInteractor.importBackup(uri) { progress ->
                        val adjusted = if (format == BackupFormat.JSON) {
                            (progress.coerceIn(0, 100) * 6) / 10
                        } else {
                            (progress.coerceIn(0, 100) * 5) / 10
                        }
                        _operationState.value = BackupOperationUiState.InProgress(
                            type = BackupOperationType.IMPORT,
                            progressPercent = adjusted.coerceIn(0, if (format == BackupFormat.JSON) 60 else 50),
                            stageMessage = strings.backupReadingFile,
                            etaSeconds = buildEtaSeconds(startedAt, adjusted.coerceAtLeast(1)),
                        )
                    }
                    when (format) {
                        BackupFormat.JSON -> {
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
                        BackupFormat.DATABASE -> {
                            _operationState.value = BackupOperationUiState.InProgress(
                                type = BackupOperationType.IMPORT,
                                progressPercent = 55,
                                stageMessage = strings.backupPreparingRestore,
                                etaSeconds = buildEtaSeconds(startedAt, 55),
                            )
                            libraryStore.importDatabaseBackup(
                                payload = payloadBytes,
                                onProgress = { progress, stage ->
                                    _operationState.value = BackupOperationUiState.InProgress(
                                        type = BackupOperationType.IMPORT,
                                        progressPercent = progress.coerceIn(55, 100),
                                        stageMessage = stage,
                                        etaSeconds = buildEtaSeconds(startedAt, progress.coerceIn(1, 100)),
                                    )
                                },
                            )
                        }
                    }
                }
                onImported()
                _operationState.value = BackupOperationUiState.Completed(
                    type = BackupOperationType.IMPORT,
                    success = true,
                    message = strings.backupImportSuccess,
                )
            }.onFailure {
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
