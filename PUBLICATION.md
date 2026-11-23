# GitHub 发布副本说明

本目录为 NUIST Pocket 1.0.0 的公开源码，包含 Android 应用源码、Gradle Wrapper、构建脚本、许可文件和当前用户说明。

发布副本不包含 Android Studio 和 Gradle 缓存、本机 SDK 路径、构建产物、APK、签名文件、调试日志、原始宣传素材、视频工程或设备数据。公开源码和 GitHub 安装包均使用合成的演示课程、考试和教室数据。

## 发布前提醒

- 从 GitHub Releases 发布 APK，不要把本机签名文件或密钥提交到仓库。
- 当前 Release 构建沿用 Android debug 签名以兼容既有测试安装。公开发行时应自行配置发布签名，且不同签名的 APK 通常不能覆盖安装。
- 登录接口为学校系统实际使用的 HTTPS 地址，项目是非官方学生应用。
- 请在 GitHub 仓库设置中启用 Secret scanning 和 push protection（若账户方案支持）。

