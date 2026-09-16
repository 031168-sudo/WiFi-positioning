package com.bitcoinprice.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BitcoinUiState {
    data object Loading : BitcoinUiState
    data class Error(val message: String) : BitcoinUiState
    data class Success(
        val priceUsd: Double,
        val changePercent24h: Double,
        val history: List<PricePoint>
    ) : BitcoinUiState
}

class BitcoinViewModel : ViewModel() {

    private val repository = BitcoinRepository()

    private val _state = MutableStateFlow<BitcoinUiState>(BitcoinUiState.Loading)
    val state: StateFlow<BitcoinUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = BitcoinUiState.Loading
        viewModelScope.launch {
            try {
                val current = withContext(Dispatchers.IO) { repository.fetchCurrentPrice() }
                val history = withContext(Dispatchers.IO) { repository.fetchFullHistory() }
                _state.value = BitcoinUiState.Success(
                    priceUsd = current.usd,
                    changePercent24h = current.changePercent24h,
                    history = history
                )
            } catch (e: Exception) {
                _state.value = BitcoinUiState.Error(e.message ?: "Не удалось загрузить данные")
            }
        }
    }
}
