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
    private var noticeRequestGeneration = 0L

    init {
        checkForNotice()
    }

    fun checkForNotice() {
        val generation = ++noticeRequestGeneration
        viewModelScope.launch {
            _uiState.update { state ->
                if (generation == noticeRequestGeneration) {
                    state.copy(isChecking = true)
                } else {
                    state
                }
            }
            val notice = remoteNoticeRepository.fetchStartupNotice()
            _uiState.update { state ->
                if (generation == noticeRequestGeneration) {
                    state.copy(
                        isChecking = false,
                        notice = notice,
                        showDialog = notice != null
                    )
                } else {
                    state
                }
            }
        }
    }

    fun dismissNotice() {
        noticeRequestGeneration += 1
        val noticeId = _uiState.value.notice?.id
        _uiState.update { it.copy(isChecking = false, showDialog = false, notice = null) }
        viewModelScope.launch {
            if (noticeId != null) {
                remoteNoticePreferences.markSeen(noticeId)
            }
        }
    }

    fun suppressForStartup() {
        noticeRequestGeneration += 1
        _uiState.update { it.copy(isChecking = false, showDialog = false) }
    }
}
