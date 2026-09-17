package com.bitcoinprice.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PricePoint(val timeSec: Long, val price: Double)

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

    /** Live BTC/USD price. blockchain.info's ticker needs no API key. */
    fun fetchCurrentPrice(): Double {
        val body = get("https://api.blockchain.info/ticker")
        val json = JSONObject(body)
        return json.getJSONObject("USD").getDouble("last")
    }

    /**
     * Daily BTC/USD market price from blockchain.info's public charts API,
     * which covers the full history back to mid-2010 in a single request.
     */
    fun fetchFullHistory(): List<PricePoint> {
        val body = get("https://api.blockchain.info/charts/market-price?timespan=all&format=json")
        val json = JSONObject(body)
        val values = json.getJSONArray("values")
        val points = ArrayList<PricePoint>(values.length())
        for (i in 0 until values.length()) {
            val entry = values.getJSONObject(i)
            val price = entry.getDouble("y")
            if (price > 0.0) {
                points.add(PricePoint(entry.getLong("x"), price))
            }
        }
        return points.sortedBy { it.timeSec }
    }
}
