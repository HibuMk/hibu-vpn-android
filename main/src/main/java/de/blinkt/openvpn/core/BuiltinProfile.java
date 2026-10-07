/*
 * 黑龙江工商学院 VPN 客户端 —— 内置线路自动导入
 *
 * 目的：用户安装后打开即有线路，只需输入账号密码，无需手动导入 .ovpn。
 *
 * 做法：首次启动时把 assets/hibu-vpn.ovpn 解析成 VpnProfile 存入 ProfileManager，
 *       并用固定 UUID，保证重复导入时是「替换」而不是「新增」。
 *
 * 说明：本项目基于 ics-openvpn (GPLv2) 二次开发，本文件为自研新增代码。
 */
package de.blinkt.openvpn.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.UUID;

import de.blinkt.openvpn.VpnProfile;

public class BuiltinProfile {

    /** 内置配置文件名（位于 app 的 assets 目录） */
    private static final String ASSET_NAME = "hibu-vpn.ovpn";

    /** 记录导入状态的偏好文件名与键 */
    private static final String PREF_NAME = "hibu_builtin_profile";
    private static final String PREF_KEY = "imported_v1";

    /** 固定 UUID：同一条线路重复导入时会被替换，不会堆积 */
    private static final String PROFILE_UUID = "c9d4a1f0-6b2e-4a83-9d17-5e0c1b7a3f28";

    /** 创建者标识，便于与用户自建线路区分 */
    private static final String PROFILE_CREATOR = "de.blinkt.openvpn.hibu.Builtin";

    /** 线路显示名 */
    private static final String PROFILE_NAME = "黑龙江工商学院 校园网";

    private BuiltinProfile() {
    }

    /**
     * 首次启动时导入内置线路；已导入则直接返回。
     * 失败不会影响 App 启动，只记录日志。
     */
    public static void ensureImported(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (sp.getBoolean(PREF_KEY, false)) {
            return;
        }

        InputStream is = null;
        try {
            is = context.getAssets().open(ASSET_NAME);

            ConfigParser cp = new ConfigParser();
            cp.parseConfig(new InputStreamReader(is, "UTF-8"));
            VpnProfile vp = cp.convertProfile();

            vp.mName = PROFILE_NAME;
            vp.mProfileCreator = PROFILE_CREATOR;
            vp.setUUID(UUID.fromString(PROFILE_UUID));

            ProfileManager pm = ProfileManager.getInstance(context);
            pm.addProfile(vp);
            ProfileManager.saveProfile(context, vp);
            pm.saveProfileList(context);

            sp.edit().putBoolean(PREF_KEY, true).apply();
            VpnStatus.logInfo("已导入内置线路：" + PROFILE_NAME);
        } catch (Exception e) {
            VpnStatus.logException("导入内置线路失败", e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
            }
        }
    }
}
