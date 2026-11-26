package pl.forma.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/** Error with a machine code the web UI maps to a Polish message: config | auth | state | denied | offline | rate | http. */
class WhoopException(val code: String, message: String, val status: Int = 0) : Exception(message)

/** Plain result of one HTTP call. */
class HttpResult(val code: Int, val body: String)

/**
 * WHOOP Developer API (v2) client without extra libraries: OAuth 2.0 authorization code flow, token refresh, paginated GETs.
 * Client ID / secret and tokens live only in SharedPreferences "forma_whoop" (never in the repo, never exported).
 * All network methods block: call them from a background thread.
 */
object WhoopClient {
    private const val PREFS = "forma_whoop"
    private const val AUTH_URL = "https://api.prod.whoop.com/oauth/oauth2/auth"
    private const val TOKEN_URL = "https://api.prod.whoop.com/oauth/oauth2/token"
    private const val BASE = "https://api.prod.whoop.com/developer"
    private const val SCOPES = "offline read:recovery read:sleep read:cycles read:workout read:profile"
    const val REDIRECT_URI = "pl.forma.app://whoop-callback"
    private const val PENDING_MAX_AGE_MS = 120000L

    private val lock = Any()

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun str(ctx: Context, key: String): String = sp(ctx).getString(key, "") ?: ""

    // ---------- credentials and status ----------
    fun setCredentials(ctx: Context, id: String, secret: String) {
        val cid = id.trim()
        val sec = secret.trim()
        val e = sp(ctx).edit()
        if (cid.isEmpty() && sec.isEmpty()) {
            e.clear()
        } else {
            if (cid != str(ctx, "client_id")) {
                e.remove("access_token").remove("refresh_token").remove("expires_at")
            }
            e.putString("client_id", cid)
            e.putString("client_secret", sec)
        }
        e.apply()
    }

    fun isConfigured(ctx: Context): Boolean =
        str(ctx, "client_id").isNotEmpty() && str(ctx, "client_secret").isNotEmpty()

    fun isConnected(ctx: Context): Boolean =
        isConfigured(ctx) && (str(ctx, "refresh_token").isNotEmpty() || str(ctx, "access_token").isNotEmpty())

    /** Never returns the secret or any token. takePending = include (and clear) a finished authorization result. */
    fun status(ctx: Context, takePending: Boolean): JSONObject {
        val o = JSONObject()
        o.put("configured", isConfigured(ctx))
        o.put("connected", isConnected(ctx))
        o.put("clientId", str(ctx, "client_id"))
        val raw = str(ctx, "pending_auth")
        if (raw.isNotEmpty() && takePending) {
            try {
                val p = JSONObject(raw)
                val age = System.currentTimeMillis() - p.optLong("at", 0L)
                if (age >= 0L && age <= PENDING_MAX_AGE_MS) {
                    p.remove("at")
                    o.put("authResult", p)
                }
            } catch (e: Exception) {
                // ignore a corrupt pending result
            }
            sp(ctx).edit().remove("pending_auth").apply()
        }
        return o
    }

    fun disconnect(ctx: Context) {
        sp(ctx).edit().remove("access_token").remove("refresh_token").remove("expires_at").remove("state").remove("pending_auth").apply()
    }

    // ---------- authorization (opened in the browser by the bridge) ----------
    fun beginAuthorize(ctx: Context): String {
        if (!isConfigured(ctx)) throw WhoopException("config", "Brak Client ID lub Client Secret")
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xff))
        val state = sb.toString()
        sp(ctx).edit().putString("state", state).apply()
        val q = LinkedHashMap<String, String>()
        q["response_type"] = "code"
        q["client_id"] = str(ctx, "client_id")
        q["redirect_uri"] = REDIRECT_URI
        q["scope"] = SCOPES
        q["state"] = state
        return AUTH_URL + "?" + form(q)
    }

    /** Called for pl.forma.app://whoop-callback?code=..&state=.. Exchanges the code and reports through emit (JSON event). */
    fun handleCallback(ctx: Context, uri: Uri, emit: (JSONObject) -> Unit) {
        val expected = str(ctx, "state")
        if (expected.isEmpty()) return // nothing pending (e.g. the same intent replayed after a restart)
        val code: String? = uri.getQueryParameter("code")
        val state: String? = uri.getQueryParameter("state")
        val error: String? = uri.getQueryParameter("error")
        Thread {
            val ev = JSONObject()
            ev.put("type", "whoopAuth")
            try {
                if (error != null) throw WhoopException("denied", error)
                if (state == null || state != expected) throw WhoopException("state", "state mismatch")
                if (code == null || code.isEmpty()) throw WhoopException("denied", "brak kodu autoryzacji")
                val p = LinkedHashMap<String, String>()
                p["grant_type"] = "authorization_code"
                p["code"] = code
                p["redirect_uri"] = REDIRECT_URI
                tokenRequest(ctx, p)
                ev.put("ok", true)
            } catch (e: WhoopException) {
                ev.put("ok", false)
                ev.put("code", e.code)
                ev.put("message", e.message ?: "")
            } catch (e: Exception) {
                ev.put("ok", false)
                ev.put("code", "http")
                ev.put("message", e.message ?: "")
            }
            sp(ctx).edit().remove("state").apply()
            val pending = JSONObject()
            pending.put("ok", ev.optBoolean("ok", false))
            pending.put("code", ev.optString("code", ""))
            pending.put("message", ev.optString("message", ""))
            pending.put("at", System.currentTimeMillis())
            sp(ctx).edit().putString("pending_auth", pending.toString()).apply()
            emit(ev)
        }.start()
    }

    // ---------- HTTP ----------
    private fun form(params: Map<String, String>): String {
        val sb = StringBuilder()
        for ((k, v) in params) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(URLEncoder.encode(k, "UTF-8").replace("+", "%20"))
            sb.append('=')
            sb.append(URLEncoder.encode(v, "UTF-8").replace("+", "%20"))
        }
        return sb.toString()
    }

    private fun openConn(url: String): HttpURLConnection {
        try {
            return URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw WhoopException("offline", e.message ?: "network")
        }
    }

    private fun http(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        val c = openConn(url)
        try {
            c.requestMethod = method
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setFixedLengthStreamingMode(bytes.size)
                val os = c.outputStream
                try {
                    os.write(bytes)
                } finally {
                    os.close()
                }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            var text = ""
            if (stream != null) {
                try {
                    text = stream.bufferedReader(Charsets.UTF_8).readText()
                } finally {
                    stream.close()
                }
            }
            return HttpResult(code, text)
        } catch (e: IOException) {
            throw WhoopException("offline", e.message ?: "network")
        } finally {
            c.disconnect()
        }
    }

    private fun parseObject(text: String): JSONObject? {
        if (text.isEmpty()) return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- tokens ----------
    private fun tokenRequest(ctx: Context, params: Map<String, String>): JSONObject {
        val id = str(ctx, "client_id")
        val secret = str(ctx, "client_secret")
        if (id.isEmpty() || secret.isEmpty()) throw WhoopException("config", "Brak Client ID lub Client Secret")
        val all = LinkedHashMap<String, String>()
        all["client_id"] = id
        all["client_secret"] = secret
        all.putAll(params)
        val headers = HashMap<String, String>()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        val res = http("POST", TOKEN_URL, headers, form(all))
        val json = parseObject(res.body)
        if (res.code < 200 || res.code > 299 || json == null || !json.has("access_token")) {
            val detail = if (json != null) json.optString("error_description", json.optString("error", "HTTP " + res.code)) else "HTTP " + res.code
            val kind = if (res.code == 400 || res.code == 401) "auth" else if (res.code == 429) "rate" else "http"
            throw WhoopException(kind, detail, res.code)
        }
        saveTokens(ctx, json)
        return json
    }

    private fun saveTokens(ctx: Context, j: JSONObject) {
        val expiresIn = j.optLong("expires_in", 3600L)
        val e = sp(ctx).edit()
        e.putString("access_token", j.optString("access_token", ""))
        val refresh = j.optString("refresh_token", "")
        if (refresh.isNotEmpty()) e.putString("refresh_token", refresh)
        e.putLong("expires_at", System.currentTimeMillis() + expiresIn * 1000L - 60000L)
        e.apply()
    }

    private fun refresh(ctx: Context) {
        synchronized(lock) {
            val rt = str(ctx, "refresh_token")
            if (rt.isEmpty()) throw WhoopException("auth", "brak refresh tokena")
            val p = LinkedHashMap<String, String>()
            p["grant_type"] = "refresh_token"
            p["refresh_token"] = rt
            p["scope"] = "offline"
            try {
                tokenRequest(ctx, p)
            } catch (e: WhoopException) {
                if (e.code == "auth") sp(ctx).edit().remove("access_token").remove("refresh_token").remove("expires_at").apply()
                throw e
            }
        }
    }

    private fun ensureAccess(ctx: Context): String {
        synchronized(lock) {
            val tok = str(ctx, "access_token")
            val exp = sp(ctx).getLong("expires_at", 0L)
            if (tok.isNotEmpty() && exp > System.currentTimeMillis()) return tok
        }
        refresh(ctx)
        val fresh = str(ctx, "access_token")
        if (fresh.isEmpty()) throw WhoopException("auth", "brak tokena dostępu")
        return fresh
    }

    // ---------- API ----------
    private fun apiGet(ctx: Context, path: String, query: Map<String, String>): JSONObject {
        var retriedAuth = false
        for (attempt in 0..2) {
            val token = ensureAccess(ctx)
            val headers = HashMap<String, String>()
            headers["Authorization"] = "Bearer " + token
            val res = http("GET", BASE + path + "?" + form(query), headers, null)
            if (res.code == 401 && !retriedAuth) {
                retriedAuth = true
                refresh(ctx)
                continue
            }
            if (res.code == 429 && attempt < 2) {
                try {
                    Thread.sleep(2000L)
                } catch (e: InterruptedException) {
                    // ignore
                }
                continue
            }
            if (res.code < 200 || res.code > 299) {
                val kind = if (res.code == 401) "auth" else if (res.code == 429) "rate" else "http"
                throw WhoopException(kind, "HTTP " + res.code + " " + path, res.code)
            }
            val j = parseObject(res.body)
            return j ?: JSONObject()
        }
        throw WhoopException("http", "zbyt wiele prób: " + path)
    }

    /** All pages of a paginated collection (limit 25, nextToken), at most 20 pages. */
    private fun fetchAll(ctx: Context, path: String, sinceIso: String): JSONArray {
        val out = JSONArray()
        var token = ""
        var pages = 0
        do {
            val q = LinkedHashMap<String, String>()
            q["limit"] = "25"
            q["start"] = sinceIso
            if (token.isNotEmpty()) q["nextToken"] = token
            val j = apiGet(ctx, path, q)
            val recs = j.optJSONArray("records")
            if (recs != null) {
                for (i in 0 until recs.length()) out.put(recs.opt(i))
            }
            token = if (j.isNull("next_token")) "" else j.optString("next_token", "")
            pages++
        } while (token.isNotEmpty() && pages < 20)
        return out
    }

    private fun fetchCycles(ctx: Context, sinceIso: String): JSONArray {
        try {
            return fetchAll(ctx, "/v2/cycle", sinceIso)
        } catch (e: WhoopException) {
            if (e.status == 404 || e.status == 400) return fetchAll(ctx, "/v1/cycle", sinceIso)
            throw e
        }
    }

    /** Recovery, sleep, workouts and cycles since the given ISO time, as the raw API records. */
    fun sync(ctx: Context, sinceIso: String): JSONObject {
        if (!isConnected(ctx)) throw WhoopException("auth", "Konto WHOOP nie jest połączone")
        val data = JSONObject()
        data.put("recovery", fetchAll(ctx, "/v2/recovery", sinceIso))
        data.put("sleep", fetchAll(ctx, "/v2/activity/sleep", sinceIso))
        data.put("workout", fetchAll(ctx, "/v2/activity/workout", sinceIso))
        var cycles = JSONArray()
        try {
            cycles = fetchCycles(ctx, sinceIso)
        } catch (e: WhoopException) {
            if (e.code == "auth" || e.code == "offline") throw e
            // strain per day is optional: carry on without it
        }
        data.put("cycle", cycles)
        return data
    }
}
