package com.paudinc.komastream.ui.viewmodel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paudinc.komastream.BuildConfig
import com.paudinc.komastream.updater.AppUpdateUiState
import com.paudinc.komastream.updater.GitHubRelease
import com.paudinc.komastream.updater.GitHubReleaseUpdater
import com.paudinc.komastream.updater.InstallUpdateResult
import com.paudinc.komastream.updater.UpdateCheckResult
import com.paudinc.komastream.utils.AppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

class UpdateController(
    private val scope: CoroutineScope,
    private val updater: GitHubReleaseUpdater,
    private val strings: AppStrings,
    private val onError: (String) -> Unit,
) {
    var updateState by mutableStateOf<AppUpdateUiState>(
        if (updater.isEnabled()) AppUpdateUiState.Idle else AppUpdateUiState.Disabled
    )
        private set

    var isDialogVisible by mutableStateOf(false)

    fun checkForUpdates(notifyIfCurrent: Boolean = false, openDialogOnUpdate: Boolean = false) {
        if (!updater.isEnabled()) {
            updateState = AppUpdateUiState.Disabled
            return
        }
        scope.launch {
            updateState = AppUpdateUiState.Checking
            when (val result = updater.checkForUpdate()) {
                UpdateCheckResult.Disabled -> updateState = AppUpdateUiState.Disabled
                UpdateCheckResult.NoUpdate -> {
                    updateState = AppUpdateUiState.UpToDate(BuildConfig.VERSION_NAME)
                }
                is UpdateCheckResult.UpdateAvailable -> {
                    updateState = AppUpdateUiState.Available(result.release)
                    if (openDialogOnUpdate) {
                        isDialogVisible = true
                    }
                }
                is UpdateCheckResult.Error -> {
                    updateState = AppUpdateUiState.Error(result.message)
                    if (notifyIfCurrent) {
                        onError(result.message)
                    }
                }
            }
        }
    }

    fun downloadUpdate(release: GitHubRelease) {
        scope.launch {
            updateState = AppUpdateUiState.Downloading(release, 0)
            runCatching {
                updater.downloadRelease(release) { progress ->
                    updateState = AppUpdateUiState.Downloading(release, progress)
                }
            }.onSuccess { file ->
                updateState = AppUpdateUiState.Downloaded(release, file)
            }.onFailure {
                updateState = AppUpdateUiState.Error(it.message ?: strings.downloadFailed)
            }
        }
    }

    fun installDownloadedUpdate(file: File) {
        when (updater.installDownloadedUpdate(file)) {
            InstallUpdateResult.Started -> {}
            InstallUpdateResult.PermissionRequired -> onError(strings.updateInstallPermissionRequired)
            InstallUpdateResult.Failed -> onError(strings.updateInstallFailed)
        }
    }
}
