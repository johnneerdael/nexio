package com.nexio.tv.notices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.notices.model.RemoteNoticeDisplay
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteNoticeUiState(
    val isChecking: Boolean = false,
    val notice: RemoteNoticeDisplay? = null,
    val showDialog: Boolean = false
)

@HiltViewModel
class RemoteNoticeViewModel @Inject constructor(
    private val remoteNoticeRepository: RemoteNoticeRepository,
    private val remoteNoticePreferences: RemoteNoticePreferences
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteNoticeUiState())
    val uiState: StateFlow<RemoteNoticeUiState> = _uiState.asStateFlow()

    init {
        checkForNotice()
    }

    fun checkForNotice() {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true) }
            val notice = remoteNoticeRepository.fetchStartupNotice()
            _uiState.update {
                it.copy(
                    isChecking = false,
                    notice = notice,
                    showDialog = notice != null
                )
            }
        }
    }

    fun dismissNotice() {
        val noticeId = _uiState.value.notice?.id
        viewModelScope.launch {
            if (noticeId != null) {
                remoteNoticePreferences.markSeen(noticeId)
            }
            _uiState.update { it.copy(showDialog = false, notice = null) }
        }
    }

    fun suppressForStartup() {
        _uiState.update { it.copy(showDialog = false, notice = null) }
    }
}
