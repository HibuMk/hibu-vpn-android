# 黑龙江工商学院 VPN 客户端（安卓版）

> **本项目基于 [ics-openvpn](https://github.com/schwabe/ics-openvpn)（OpenVPN for Android，作者 Arne Schwabe）二次开发。**
> 按上游 GPLv2 许可要求，本仓库**完整开源**（含上游全部提交历史）。

---

## 相对上游的改动

| 项 | 说明 |
|---|---|
| **品牌化** | 应用名 → 「黑龙江工商学院VPN客户端」；图标 → 校徽（mdpi~xxxhdpi 五档，方形+圆形）；主色 #005397 |
| **内置线路** | `main/src/main/assets/hibu-vpn.ovpn` —— 安装后即有线路，用户只需输入账号密码，无需导入配置 |
| **界面简化**（进行中） | 隐藏高级配置管理，突出「账号 + 密码 + 连接」 |

## 编译

本项目使用 GitHub Actions 自动构建，产物为 APK：

- workflow：`.github/workflows/build.yaml`
- 目标：`UiOvpn23`（完整界面 + OpenVPN 3 核心，minSdk 23）
- 本地构建：`./gradlew assembleUiOvpn23Release`

## 署名与许可

- **上游项目**：OpenVPN for Android（ics-openvpn），Copyright (c) 2012-2022 Arne Schwabe
- **上游许可**：GNU GPL v2 **with additional terms**，全文见 `doc/LICENSE.txt`
- **第三方组件**：OpenVPN（GPLv2）、OpenSSL、mbedTLS、LZ4、Asio、fmt 等，各自许可见上游 `doc/`
- **本项目修改**：黑龙江工商学院信息中心（网信办）

```
This program is free software; you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation; either version 2 of the License, or (at your option) any later version.
```

## 关于校园网使用

本客户端用于黑龙江工商学院校园网 VPN 接入，认证由学校统一身份认证 / RADIUS 完成。
客户端本身不收集、不上传任何用户信息；账号密码仅用于向学校 VPN 网关认证。
