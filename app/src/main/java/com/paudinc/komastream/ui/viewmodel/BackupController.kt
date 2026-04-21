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

class BackupController(
    private val scope: CoroutineScope,
    private val libraryStore: LibraryStore,
    private val backupFileInteractor: BackupFileInteractor,
    private val strings: AppStrings,
) {
    private val _operationState = MutableStateFlow<BackupOperationUiState>(BackupOperationUiState.Idle)
    val operationState: StateFlow<BackupOperationUiState> = _operationState.asStateFlow()

    fun exportBackup(uri: Uri) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    _operationState.value = BackupOperationUiState.InProgress(
                        type = BackupOperationType.EXPORT,
                        progressPercent = 0,
                    )
                    val bytes = libraryStore.exportBackup().toByteArray()
                    backupFileInteractor.exportBackup(uri, bytes) { progress ->
                        _operationState.value = BackupOperationUiState.InProgress(
                            type = BackupOperationType.EXPORT,
                            progressPercent = progress,
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
                    _operationState.value = BackupOperationUiState.InProgress(
                        type = BackupOperationType.IMPORT,
                        progressPercent = 0,
                    )
                    val payloadBytes = backupFileInteractor.importBackup(uri) { progress ->
                        _operationState.value = BackupOperationUiState.InProgress(
                            type = BackupOperationType.IMPORT,
                            progressPercent = progress.coerceIn(0, 95),
                        )
                    }
                    _operationState.value = BackupOperationUiState.InProgress(
                        type = BackupOperationType.IMPORT,
                        progressPercent = 98,
                    )
                    val json = payloadBytes.toString(Charsets.UTF_8)
                    libraryStore.importBackup(
                        payload = json,
                        selectedProviderIdFallback = selectedProviderIdFallback,
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
