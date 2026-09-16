package com.bitcoinprice.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: BitcoinViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                BitcoinPriceScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitcoinPriceScreen(viewModel: BitcoinViewModel) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Курс Bitcoin (BTC/USD)") })
        }
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is BitcoinUiState.Loading -> LoadingContent()
                is BitcoinUiState.Error -> ErrorContent(s.message) { viewModel.refresh() }
                is BitcoinUiState.Success -> SuccessContent(s) { viewModel.refresh() }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Ошибка загрузки данных", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Повторить") }
    }
}

@Composable
private fun SuccessContent(state: BitcoinUiState.Success, onRefresh: () -> Unit) {
    val todayLabel = remember(state) {
        SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date())
    }
    val changeColor = if (state.changePercent24h >= 0) Color(0xFF1E8E3E) else Color(0xFFD93025)
    val changeSign = if (state.changePercent24h >= 0) "+" else ""

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Курс на $todayLabel",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = formatUsd(state.priceUsd),
            fontSize = 52.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$changeSign${"%.2f".format(state.changePercent24h)}% за 24ч",
            color = changeColor,
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "История с 2010 года по сегодня (логарифмическая шкала)",
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (state.history.isNotEmpty()) {
            PriceHistoryChart(points = state.history, modifier = Modifier.fillMaxWidth())
        } else {
            Text("Нет исторических данных", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onRefresh) { Text("Обновить") }
        }
    }
}

private fun formatUsd(price: Double): String = "$" + "%,.2f".format(price)
