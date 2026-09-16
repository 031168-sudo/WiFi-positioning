package com.bitcoinprice.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PricePoint(val timeSec: Long, val price: Double)

data class CurrentPrice(val usd: Double, val changePercent24h: Double)

class BitcoinRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code} for $url")
            }
            return response.body?.string() ?: throw java.io.IOException("Empty response from $url")
        }
    }

    fun fetchCurrentPrice(): CurrentPrice {
        val body = get("https://min-api.cryptocompare.com/data/pricemultifull?fsyms=BTC&tsyms=USD")
        val json = JSONObject(body)
        val raw = json.getJSONObject("RAW").getJSONObject("BTC").getJSONObject("USD")
        return CurrentPrice(
            usd = raw.getDouble("PRICE"),
            changePercent24h = raw.optDouble("CHANGEPCT24HOUR", 0.0)
        )
    }

    /**
     * Daily close prices from as far back as CryptoCompare has data (BTC trading
     * data effectively begins mid-2010) up to today, paginated 2000 days per call.
     */
    fun fetchFullHistory(): List<PricePoint> {
        val targetStartSec = 1262304000L // 2010-01-01T00:00:00Z
        val points = LinkedHashMap<Long, Double>()
        var toTs: Long? = null
        var pagesLeft = 6

        while (pagesLeft > 0) {
            pagesLeft--
            val url = buildString {
                append("https://min-api.cryptocompare.com/data/v2/histoday?fsym=BTC&tsym=USD&limit=2000")
                if (toTs != null) append("&toTs=$toTs")
            }
            val body = get(url)
            val json = JSONObject(body)
            if (json.optString("Response") != "Success") break
            val data = json.getJSONObject("Data").getJSONArray("Data")
            if (data.length() == 0) break

            var earliest = Long.MAX_VALUE
            for (i in 0 until data.length()) {
                val entry = data.getJSONObject(i)
                val time = entry.getLong("time")
                val close = entry.getDouble("close")
                if (close > 0.0) points[time] = close
                if (time < earliest) earliest = time
            }

            if (earliest <= targetStartSec || earliest == Long.MAX_VALUE) break
            toTs = earliest - 86400
        }

        return points.entries
            .map { PricePoint(it.key, it.value) }
            .sortedBy { it.timeSec }
    }
}
