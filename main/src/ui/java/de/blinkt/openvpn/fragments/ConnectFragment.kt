/*
 * 黑龙江工商学院 VPN 客户端 —— 主连接页
 *
 * 界面与 PC 客户端（index.html）逐项对齐：
 *   状态圆环 → 节点/账号/密码 → 复选框 → 连接按钮 → 连接信息 → 页脚
 *
 * 安全约定（遵从「所有代码禁止明文保存密码」）：
 *   · 账号   → 可持久化（非机密），存 SharedPreferences
 *   · 密码   → 仅内存。通过 LaunchVPN.EXTRA_AUTH_PW 传入，落入
 *              mTransientAuthPW / PasswordCache（进程内存），
 *              绝不写入 VpnProfile.mPassword，绝不落盘。
 *
 * 本文件基于 ics-openvpn（GPLv2 + 附加条款）二次开发，
 * 上游项目：https://github.com/schwabe/ics-openvpn
 */

package de.blinkt.openvpn.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.updater.UpdateManager
import java.util.Locale

class ConnectFragment : Fragment(), VpnStatus.StateListener, VpnStatus.ByteCountListener {

    private var mService: IOpenVPNServiceInternal? = null
    private var mProfile: VpnProfile? = null

    private lateinit var ring: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var statusMsg: TextView
    private lateinit var nodeSpinner: Spinner
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var rememberUser: CheckBox
    private lateinit var autoConnect: CheckBox
    private lateinit var actionBtn: Button
    private lateinit var infoState: TextView
    private lateinit var infoNode: TextView
    private lateinit var infoDuration: TextView
    private lateinit var infoTraffic: TextView

    private var mLevel: ConnectionStatus = ConnectionStatus.LEVEL_NOTCONNECTED

    /** 视图是否已就绪（onCreateView 结束置 true，防止状态回调早于 findViewById） */
    private var viewsReady = false

    /** 账号登录区当前锁定态（null=未设置过），避免重复刷 UI */
    private var formLocked: Boolean? = null
    private var mConnectedAt: Long = 0L
    private var mTickRunning = false

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            mService = IOpenVPNServiceInternal.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mService = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_connect, container, false)

        ring = v.findViewById(R.id.ring)
        statusText = v.findViewById(R.id.status_text)
        statusMsg = v.findViewById(R.id.status_msg)
        nodeSpinner = v.findViewById(R.id.node_spinner)
        userInput = v.findViewById(R.id.user_input)
        passInput = v.findViewById(R.id.pass_input)
        rememberUser = v.findViewById(R.id.remember_user)
        autoConnect = v.findViewById(R.id.auto_connect)
        actionBtn = v.findViewById(R.id.action_btn)
        infoState = v.findViewById(R.id.info_state)
        infoNode = v.findViewById(R.id.info_node)
        infoDuration = v.findViewById(R.id.info_duration)
        infoTraffic = v.findViewById(R.id.info_traffic)

        setupProfile()
        setupPrefs()

        actionBtn.setOnClickListener { onActionPressed() }

        // 关于：软件信息 + 运行日志（导航栏取消后，日志入口挪到这里）
        v.findViewById<TextView>(R.id.about_link).setOnClickListener { showAbout() }

        // 首次进入自动连接
        if (autoConnect.isChecked && mLevel == ConnectionStatus.LEVEL_NOTCONNECTED) {
            autoConnectIfPossible()
        }

        // ★ 强制一屏显示：内容高于屏幕可用高度时整体等比缩放，任何屏幕都不用滑动
        val root = v.findViewById<View>(R.id.connect_root)
        root.post { autoFitToScreen(root) }
        root.viewTreeObserver.addOnGlobalLayoutListener { autoFitToScreen(root) }

        viewsReady = true
        // 初始锁态（若进入本页时 VPN 已在连接/已连接）
        setFormLocked(
            mLevel == ConnectionStatus.LEVEL_CONNECTED ||
            mLevel == ConnectionStatus.LEVEL_START ||
            mLevel == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ||
            mLevel == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED
        )

        // ★ OTA：启动时静默检查一次（有新版本才弹窗，6 小时内不重复请求）
        (activity as? android.app.Activity)?.let { UpdateManager.checkOnLaunch(it) }

        return v
    }

    /**
     * 锁定 / 解锁「账号登录」区
     * 连接中与已连接时：控件置灰、不可编辑、不可点选（防止连接期间改账号导致状态错乱）
     */
    private fun setFormLocked(locked: Boolean) {
        if (!viewsReady || formLocked == locked) return
        formLocked = locked
        val alpha = if (locked) 0.45f else 1f
        for (v in arrayOf<View>(nodeSpinner, userInput, passInput, rememberUser, autoConnect)) {
            v.isEnabled = !locked
            v.alpha = alpha
        }
    }

    /**
     * 强制一页显示（不可滑动）
     * 控件总高 > 屏幕可视高度时，把整块内容等比缩放（scaleX == scaleY，不变形），
     * 缩放后内容完整可见且无滚动容器 → 无论如何都滑不动。
     * 屏幕够大时自动恢复 1.0（不放大，避免糊）。
     */
    private fun autoFitToScreen(root: View) {
        val host = root.parent as? View ?: return
        val avail = host.height
        val need = root.measuredHeight          // measuredHeight 不受 scale 影响，就是自然高度
        if (avail <= 0 || need <= 0) return

        val s = if (need > avail) avail.toFloat() / need else 1f
        if (Math.abs(root.scaleY - s) < 0.004f) return   // 已适配，避免无限重排

        root.pivotX = root.width / 2f
        root.pivotY = 0f                                  // 从顶部开始缩，保持顶对齐
        root.scaleX = s
        root.scaleY = s
    }

    /** 找到内置线路（安装时自动导入的那一条） */
    private fun setupProfile() {
        val pm = ProfileManager.getInstance(requireContext())
        val profiles = pm.profiles
        mProfile = profiles.firstOrNull()

        val name = mProfile?.mName ?: getString(R.string.pc_node_default)
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listOf(name)
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        nodeSpinner.adapter = adapter
        nodeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                infoNode.text = name
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        infoNode.text = name

        if (mProfile == null) {
            statusMsg.text = getString(R.string.pc_no_profile)
            actionBtn.isEnabled = false
        }
    }

    private fun prefs() = requireContext().getSharedPreferences("hibu_vpn_ui", Context.MODE_PRIVATE)

    private fun setupPrefs() {
        val p = prefs()
        val savedUser = p.getString("username", "") ?: ""
        userInput.setText(savedUser)
        rememberUser.isChecked = savedUser.isNotEmpty()
        autoConnect.isChecked = p.getBoolean("auto_connect", false)

        autoConnect.setOnCheckedChangeListener { _, checked ->
            p.edit().putBoolean("auto_connect", checked).apply()
        }
        rememberUser.setOnCheckedChangeListener { _, checked ->
            if (!checked) p.edit().remove("username").apply()
        }
    }

    private fun autoConnectIfPossible() {
        val p = prefs()
        // 开机自动连接仅在已记住账号时生效（密码不落盘，故仍需用户输入密码）
        if ((p.getString("username", "") ?: "").isNotEmpty()) {
            userInput.requestFocus()
        }
    }

    private fun onActionPressed() {
        if (mLevel == ConnectionStatus.LEVEL_CONNECTED ||
            mLevel == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED ||
            mLevel == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ||
            mLevel == ConnectionStatus.LEVEL_START
        ) {
            disconnect()
        } else {
            connect()
        }
    }

    private fun connect() {
        val profile = mProfile
        if (profile == null) {
            toast(getString(R.string.pc_no_profile))
            return
        }

        val user = userInput.text.toString().trim()
        val pass = passInput.text.toString()
        if (user.isEmpty()) {
            toast(getString(R.string.pc_err_user))
            userInput.requestFocus()
            return
        }
        if (pass.isEmpty()) {
            toast(getString(R.string.pc_err_pass))
            passInput.requestFocus()
            return
        }

        // 账号：可选持久化（非机密）
        if (rememberUser.isChecked) {
            prefs().edit().putString("username", user).apply()
        } else {
            prefs().edit().remove("username").apply()
        }

        // 启动 LaunchVPN，凭据经 intent 传入：
        //   用户名 → 写入配置；密码 → mTransientAuthPW（仅内存）
        val intent = Intent(requireContext(), LaunchVPN::class.java)
        intent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUIDString())
        intent.putExtra(LaunchVPN.EXTRA_AUTH_USER, user)
        intent.putExtra(LaunchVPN.EXTRA_AUTH_PW, pass)
        intent.putExtra(OpenVPNService.EXTRA_START_REASON, getString(R.string.pc_start_reason))
        // 点击连接后不要跳到日志页（intent 级锁死，配合 showlogwindow=false 双保险）
        intent.putExtra(LaunchVPN.EXTRA_HIDELOG, true)
        intent.action = Intent.ACTION_MAIN
        startActivity(intent)

        // 立即清空密码输入框，不在界面/内存里多留
        passInput.setText("")
    }

    private fun disconnect() {
        ProfileManager.setConnectedVpnProfileDisconnected(requireContext())
        try {
            mService?.stopVPN(false)
        } catch (e: RemoteException) {
            VpnStatus.logException(e)
        }
    }

    /** 关于弹窗：软件信息 + 一键查看运行日志 */
    private fun showAbout() {
        val body = StringBuilder()
        body.append(getString(R.string.pc_about_body))
        body.append("\n\n")
        body.append(getString(R.string.pc_about_version)).append("：")
        body.append(
            try {
                requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).versionName ?: "—"
            } catch (e: Exception) {
                "—"
            }
        )

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.pc_about_title)
            .setMessage(body.toString())
            .setPositiveButton(R.string.pc_about_log) { _, _ -> showLog() }
            .setNeutralButton(R.string.pc_update_check) { _, _ ->
                (activity as? android.app.Activity)?.let { UpdateManager.checkManually(it) }
            }
            .setNegativeButton(R.string.pc_about_close, null)
            .show()
    }

    /** 运行日志（原来在导航栏的「图表」页，现在从「关于」进入） */
    private fun showLog() {
        val log = try {
            VpnStatus.getLastCleanLogMessage(requireContext(), true) ?: ""
        } catch (e: Exception) {
            ""
        }
        val sv = android.widget.ScrollView(requireContext())
        val tv = TextView(requireContext())
        tv.text = log.ifBlank { getString(R.string.pc_about_close) }
        tv.textSize = 10f
        tv.setTextIsSelectable(true)
        val pad = (12 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad, pad, pad)
        sv.addView(tv)

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.pc_log_title)
            .setView(sv)
            .setPositiveButton(R.string.pc_about_close, null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    // ── 生命周期 ──

    override fun onResume() {
        super.onResume()
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)
        val intent = Intent(requireActivity(), OpenVPNService::class.java)
        intent.action = OpenVPNService.START_SERVICE
        requireActivity().bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        super.onPause()
        VpnStatus.removeStateListener(this)
        VpnStatus.removeByteCountListener(this)
        try {
            requireActivity().unbindService(mConnection)
        } catch (ignored: IllegalArgumentException) {
        }
        mService = null
    }

    // ── 状态回调 ──

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus,
        Intent: Intent?
    ) {
        val activity = activity ?: return
        val cleanMsg = try {
            VpnStatus.getLastCleanLogMessage(activity, true)
        } catch (e: Exception) {
            logmessage ?: ""
        }

        activity.runOnUiThread {
            mLevel = level
            applyState(level, cleanMsg)
        }
    }

    override fun setConnectedVPN(uuid: String?) {}

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        val activity = activity ?: return
        activity.runOnUiThread {
            infoTraffic.text = String.format(
                Locale.US, "↓ %s  ↑ %s", human(inBytes), human(outBytes)
            )
        }
    }

    private fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun applyState(level: ConnectionStatus, msg: String?) {
        val connected = level == ConnectionStatus.LEVEL_CONNECTED
        val waiting = level == ConnectionStatus.LEVEL_START ||
                level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED ||
                level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET
        val failed = level == ConnectionStatus.LEVEL_AUTH_FAILED ||
                level == ConnectionStatus.LEVEL_NONETWORK

        val color: Int = when {
            connected -> R.color.ok
            waiting -> R.color.brand
            failed -> R.color.err
            else -> R.color.ink3
        }
        val bgDrawable: Int = if (waiting) R.drawable.pc_ring_wait else R.drawable.pc_ring
        ring.setBackgroundResource(bgDrawable)
        ring.backgroundTintList = ColorStateList.valueOf(
            requireContext().getColor(color)
        )

        val title: String = when {
            connected -> getString(R.string.pc_status_on)
            waiting -> getString(R.string.pc_status_wait)
            failed -> getString(R.string.pc_status_err)
            else -> getString(R.string.pc_status_off)
        }
        statusText.text = title
        statusText.setTextColor(requireContext().getColor(color))
        // 圆环下的小字：若引擎日志与标题重复（如都显示"已连接"），改显示提示语避免重复
        val rawMsg = msg?.trim().orEmpty()
        statusMsg.text = if (rawMsg.isNotEmpty() && !rawMsg.equals(title, ignoreCase = true)) {
            rawMsg
        } else {
            getString(R.string.pc_status_hint)
        }

        actionBtn.text = when {
            connected -> getString(R.string.pc_btn_disconnect)
            waiting -> getString(R.string.pc_btn_connecting)
            else -> getString(R.string.pc_btn_connect)
        }
        actionBtn.isEnabled = !waiting

        // ★ 拨号成功（含连接中）后锁定账号登录框：灰色、不可修改录入
        setFormLocked(connected || waiting)

        if (connected) {
            actionBtn.setBackgroundResource(R.drawable.pc_button_disc)
            actionBtn.setTextColor(requireContext().getColor(R.color.err))
        } else {
            actionBtn.setBackgroundResource(R.drawable.pc_button)
            actionBtn.setTextColor(requireContext().getColor(android.R.color.white))
        }

        infoState.text = title

        // 连接时长
        if (connected) {
            if (mConnectedAt == 0L) mConnectedAt = System.currentTimeMillis()
            startTick()
        } else {
            mConnectedAt = 0L
            if (level == ConnectionStatus.LEVEL_NOTCONNECTED) {
                infoDuration.text = "—"
                infoTraffic.text = "—"
            }
        }
    }

    private fun startTick() {
        if (mTickRunning) return
        mTickRunning = true
        val v = view ?: return
        v.post(object : Runnable {
            override fun run() {
                if (mConnectedAt > 0) {
                    val s = (System.currentTimeMillis() - mConnectedAt) / 1000
                    infoDuration.text = String.format(
                        Locale.US, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60
                    )
                    v.postDelayed(this, 1000)
                } else {
                    mTickRunning = false
                }
            }
        })
    }
}
