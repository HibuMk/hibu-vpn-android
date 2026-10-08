package de.blinkt.openvpn.updater

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import de.blinkt.openvpn.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 黑龙江工商学院 VPN 客户端 —— OTA 在线升级
 *
 * 版本清单放在校内网盘（cloud.hibu.edu.cn，公网可达），格式：
 * {
 *   "versionCode": 226,
 *   "versionName": "0.7.70",
 *   "url": "https://cloud.hibu.edu.cn/s/xxxx/download",
 *   "changelog": "① … ② …",
 *   "force": false
 * }
 *
 * 规则：
 *   · versionCode 大于本机 → 提示更新（force=true 时不显示「稍后」）
 *   · 下载到 getExternalFilesDir("updates")，通过 FileProvider 交给系统安装器
 *   · 安卓 8+ 需「安装未知应用」权限，未授权则引导去设置页
 *
 * 说明：本 App 为 GPLv2 换壳的校内客户端，升级包同样以 APK 形式分发（校内 In-house）。
 */
object UpdateManager {

    private const val TAG = "HibuUpdate"

    /** 版本清单地址（校内网盘直链，改这里即可换源） */
    private const val MANIFEST_URL = "https://cloud.hibu.edu.cn/s/PfwsPfoM5JfW2sW/download"

    /** 自动检查的节流间隔（6 小时） */
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private const val PREFS = "hibu_vpn_update"
    private const val KEY_LAST_CHECK = "last_check"

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val changelog: String,
        val force: Boolean
    )

    // ── 本机版本 ──────────────────────────────────────────────

    fun currentVersionCode(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) {
        0
    }

    fun currentVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "—"
    } catch (e: Exception) {
        "—"
    }

    // ── 检查 ─────────────────────────────────────────────────

    /** 手动检查：无论有没有新版都给反馈 */
    fun checkManually(act: Activity) {
        fetch(act) { rel ->
            when {
                rel == null -> toast(act, act.getString(R.string.pc_update_fail))
                rel.versionCode <= currentVersionCode(act) ->
                    AlertDialog.Builder(act)
                        .setTitle(R.string.pc_update_title)
                        .setMessage(
                            act.getString(R.string.pc_update_latest) + "\n\n" +
                                    act.getString(R.string.pc_about_version) + "：" + currentVersionName(act)
                        )
                        .setPositiveButton(R.string.pc_about_close, null)
                        .show()
                else -> prompt(act, rel)
            }
        }
    }

    /** 启动时自动检查：有新版本才弹窗；6 小时内不重复请求 */
    fun checkOnLaunch(act: Activity) {
        val sp = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong(KEY_LAST_CHECK, 0L) < AUTO_CHECK_INTERVAL_MS) return
        fetch(act) { rel ->
            sp.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (rel != null && rel.versionCode > currentVersionCode(act)) prompt(act, rel)
        }
    }

    private fun prompt(act: Activity, rel: Release) {
        val body = StringBuilder()
        body.append(act.getString(R.string.pc_update_found)).append("\n")
        body.append("  ").append(act.getString(R.string.pc_about_version)).append("：")
        body.append(currentVersionName(act)).append("  →  ").append(rel.versionName).append("\n")
        if (rel.changelog.isNotBlank()) {
            body.append("\n").append(act.getString(R.string.pc_update_changelog)).append("\n")
            body.append(rel.changelog)
        }
        val dlg = AlertDialog.Builder(act)
            .setTitle(R.string.pc_update_title)
            .setMessage(body.toString())
            .setPositiveButton(R.string.pc_update_now) { _, _ -> download(act, rel) }
        if (!rel.force) dlg.setNegativeButton(R.string.pc_update_later, null)
        dlg.setCancelable(!rel.force).show()
    }

    private fun fetch(ctx: Context, onDone: (Release?) -> Unit) {
        val cb = { r: Release? -> (ctx as? Activity)?.runOnUiThread { onDone(r) } ?: onDone(r) }
        Thread {
            var result: Release? = null
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "HIBU-VPN-Android")
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(text)
                result = Release(
                    j.optInt("versionCode", 0),
                    j.optString("versionName", ""),
                    j.optString("url", ""),
                    j.optString("changelog", ""),
                    j.optBoolean("force", false)
                )
            } catch (e: Exception) {
                Log.i(TAG, "检查更新失败: ${e.message}")
                result = null
            } finally {
                try { conn?.disconnect() } catch (ignored: Exception) {}
            }
            cb(result)
        }.start()
    }

    // ── 下载 + 安装 ───────────────────────────────────────────

    private fun download(act: Activity, rel: Release) {
        if (rel.url.isBlank()) {
            toast(act, act.getString(R.string.pc_update_fail))
            return
        }
        val pd = ProgressDialog(act).apply {
            setTitle(R.string.pc_update_downloading)
            setMessage(rel.versionName)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setMax(100)
            setCancelable(false)
            show()
        }

        Thread {
            var file: File? = null
            try {
                val dir = File(act.getExternalFilesDir(null), "updates").apply { mkdirs() }
                val out = File(dir, "hibu-vpn-update.apk")
                val conn = (URL(rel.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "HIBU-VPN-Android")
                }
                val total = conn.contentLength.toLong()
                conn.inputStream.use { ins ->
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (ins.read(buf).also { read = it } > 0) {
                            fos.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val p = ((done * 100) / total).toInt()
                                act.runOnUiThread { if (pd.isShowing) pd.progress = p }
                            }
                        }
                        fos.flush()
                    }
                }
                conn.disconnect()
                file = out
            } catch (e: Exception) {
                Log.i(TAG, "下载失败: ${e.message}")
                file = null
            }

            val apk = file
            act.runOnUiThread {
                if (pd.isShowing) pd.dismiss()
                if (apk == null || !apk.exists() || apk.length() < 1024) {
                    toast(act, act.getString(R.string.pc_update_fail))
                } else {
                    install(act, apk)
                }
            }
        }.start()
    }

    /** 调起系统安装器（安卓 8+ 需先拿到「安装未知应用」授权） */
    private fun install(act: Activity, apk: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !act.packageManager.canRequestPackageInstalls()
            ) {
                toast(act, act.getString(R.string.pc_update_perm))
                act.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.packageName)
                    )
                )
                return
            }
            val uri = FileProvider.getUriForFile(
                act, act.packageName + ".hibuupdate", apk
            )
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            act.startActivity(i)
        } catch (e: Exception) {
            Log.i(TAG, "安装失败: ${e.message}")
            toast(act, act.getString(R.string.pc_update_fail))
        }
    }

    private fun toast(act: Activity, s: String) {
        act.runOnUiThread { Toast.makeText(act, s, Toast.LENGTH_LONG).show() }
    }
}
