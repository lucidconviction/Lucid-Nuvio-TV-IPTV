package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.IptvOrgCatalog
import com.nuvio.tv.livetv.data.IptvOrgCatalogData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IptvOrgViewModel @Inject constructor(
    private val catalog: IptvOrgCatalog,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val data: IptvOrgCatalogData? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private var job: Job? = null

    fun load(force: Boolean = false) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { catalog.load(force) }
                .onSuccess { data ->
                    _state.value = UiState(loading = false, data = data)
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "Could not load the iptv-org lists",
                        )
                    }
                }
        }
    }
}
