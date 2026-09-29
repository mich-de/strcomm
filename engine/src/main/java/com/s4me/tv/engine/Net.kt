package com.s4me.tv.engine

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

const val DESKTOP_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** One cookie jar shared by every request, mirroring the addon's single persistent MozillaCookieJar. */
private class SharedCookieJar : CookieJar {
  private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    if (cookies.isEmpty()) return
    val bucket = store.getOrPut(url.host) { mutableListOf() }
    synchronized(bucket) {
      for (c in cookies) {
        bucket.removeAll { it.name == c.name }
        bucket.add(c)
      }
    }
  }

  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    val now = System.currentTimeMillis()
    val bucket = store[url.host] ?: return emptyList()
    return synchronized(bucket) { bucket.filter { it.expiresAt > now } }
  }
}

private class DefaultHeadersInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val original = chain.request()
    val builder = original.newBuilder()
    if (original.header("User-Agent") == null) builder.header("User-Agent", DESKTOP_UA)
    if (original.header("Accept-Language") == null) {
      builder.header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3")
    }
    if (original.header("Accept") == null) {
      builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    }
    return chain.proceed(builder.build())
  }
}

/** Thin OkHttp wrapper standing in for the addon's core/httptools.py. */
object Net {
  private val sharedCookieJar = SharedCookieJar()

  val client: OkHttpClient =
    OkHttpClient.Builder()
      .cookieJar(sharedCookieJar)
      .addInterceptor(DefaultHeadersInterceptor())
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build()

  /** Same as [client] but does not auto-follow redirects, for hosts that hand back the final link via `Location`. */
  val noRedirectClient: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

  suspend fun get(url: String, headers: Map<String, String> = emptyMap(), referer: String? = null): String =
    request(client, Request.Builder().url(url).also { applyHeaders(it, headers, referer) }.build())

  /** Like [get], but also returns the URL actually landed on after redirects (relative links on the page resolve against this, not [url]). */
  suspend fun getWithFinalUrl(url: String, headers: Map<String, String> = emptyMap(), referer: String? = null): Pair<String, String> =
    withContext(Dispatchers.IO) {
      val req = Request.Builder().url(url).also { applyHeaders(it, headers, referer) }.build()
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw HttpStatusException(resp.code, req.url.toString())
        resp.request.url.toString() to resp.body.string()
      }
    }

  suspend fun postForm(
    url: String,
    fields: Map<String, String>,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
  ): String {
    val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
    val req = Request.Builder().url(url).post(body).also { applyHeaders(it, headers, referer) }.build()
    return request(client, req)
  }

  suspend fun postJson(
    url: String,
    json: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
  ): String {
    val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
    val req = Request.Builder().url(url).post(body).also { applyHeaders(it, headers, referer) }.build()
    return request(client, req)
  }

  /** Follows redirects manually up to [maxHops], returning the last `Location` seen without downloading its body. */
  suspend fun resolveRedirectChain(
    url: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
    maxHops: Int = 5,
  ): String {
    var current = url
    repeat(maxHops) {
      val req = Request.Builder().url(current).also { applyHeaders(it, headers, referer) }.build()
      val location =
        withContext(Dispatchers.IO) {
          noRedirectClient.newCall(req).execute().use { resp -> if (resp.isRedirect) resp.header("Location") else null }
        } ?: return current
      current = current.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location
    }
    return current
  }

  private fun applyHeaders(builder: Request.Builder, headers: Map<String, String>, referer: String?) {
    headers.forEach { (k, v) -> builder.header(k, v) }
    referer?.let { builder.header("Referer", it) }
  }

  private suspend fun request(httpClient: OkHttpClient, req: Request): String =
    withContext(Dispatchers.IO) {
      httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw HttpStatusException(resp.code, req.url.toString())
        resp.body.string()
      }
    }
}
