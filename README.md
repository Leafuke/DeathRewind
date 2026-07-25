# Death Rewind 2.0

Death Rewind 是 MineBackup 的死亡回溯附属模组。它会在正常游玩期间定时请求
MineBackup 创建检查点，并在单人游戏的死亡界面提供一键回溯入口。

2.0 起，Death Rewind 不再直接连接 FolderRewind，也不再复制 MineBackup 的热保存、
自动保存冻结、恢复和重连实现。所有世界操作均由 MineBackup API v2 统一协调。

## 支持范围

- Minecraft 26.1～26.1.2
- Fabric Loader 0.18.4+
- Fabric API
- MineBackup 3.1.0+
- Java 25
- 单人世界及 LAN 世界房主

Death Rewind 2.0 不支持专用服务器，也不能让加入 LAN 世界的普通客户端发起世界恢复。
在专用服务器上加载时，模组只记录禁用提示，不创建检查点或提交恢复请求。

## 安装

1. 安装并配置 FolderRewind 及其 Minecraft 扩展。
2. 安装 Fabric Loader、Fabric API 和 MineBackup 3.1.0 或更高版本。
3. 将 Death Rewind Fabric-26.1 的 JAR 放入同一个 `mods` 目录。
4. 进入单人世界。首次启动会生成 `config/death-rewind.json`。

MineBackup 是必需依赖；缺少或版本低于 3.1.0 时，Fabric Loader 会拒绝加载 Death
Rewind。

## 工作方式

### 定时检查点

Death Rewind 按服务器实际运行 Tick 计算间隔。单人游戏暂停、世界未运行、已有 DR
检查点请求执行中或死亡界面打开时，计时不会推进。

达到间隔后，DR 通过 MineBackup API v2 为当前世界请求一次备份，并传递配置中的完整/
增量模式与压缩参数。同一时间最多存在一个 DR 请求；如果 MineBackup 正忙、后端失败或
请求异常，本次检查点不会排队或立即重试，而是在下个完整周期再次尝试。

DR 的定时器与 MineBackup 的 `/mb auto` 是两套独立计划。两者可以同时启用，但可能在
相近时间分别请求备份；MineBackup 的世界操作门会阻止它们并发修改同一世界。

### 死亡回溯

死亡界面会在原版按钮下方增加“回溯到若干分钟前”按钮。按钮等待原版 20 Tick
防误触延迟结束，并仅在以下条件满足时启用：

- 当前游戏由本机集成服务器承载；
- MineBackup 报告当前世界可操作；
- MineBackup 当前没有其他备份、目录或恢复操作；
- DR 没有已经提交的恢复请求。

点击按钮会立即请求 MineBackup 恢复当前世界的**全局最新归档**，不经过 `/mb restore`
的聊天倒计时。最新归档可能由 DR、JEA、MineBackup 自动备份或管理员手动备份创建，
不保证属于 DR。

从世界保存、玩家断开、FolderRewind 恢复到客户端自动重连，整个生命周期均由
MineBackup 负责。请求被拒绝、异常或恢复失败时，DR 会显示原因并解除强制模式对原版
按钮的限制。

### 归档保留

DR 检查点、JEA 快照和普通 MineBackup/FolderRewind 备份共用相同的归档保留策略。
Death Rewind 不提供独立配额、固定槽位或归档保护。首次定时检查点完成前，如果
FolderRewind 中没有任何当前世界归档，死亡回溯会失败。

## 配置

默认的 `config/death-rewind.json`：

```json
{
  "schemaVersion": 1,
  "enabled": true,
  "intervalMinutes": 5,
  "showBackupInfo": true,
  "forceDeathRewind": false,
  "backup": {
    "mode": "incremental",
    "compressionMethod": "zstd",
    "compressionLevel": 11
  }
}
```

- `enabled`：是否为新服务器会话启用 DR。
- `intervalMinutes`：检查点间隔，范围为 1～1440 分钟。
- `showBackupInfo`：是否在聊天栏显示检查点结果。
- `forceDeathRewind`：回溯可用或已经提交时禁用原版死亡按钮。MineBackup 忙碌、
  不可用或恢复失败时会自动安全解锁。
- `backup.mode`：`full` 或 `incremental`。
- `backup.compressionMethod`：`LZMA2`、`Deflate`、`BZip2` 或 `zstd`。
- `backup.compressionLevel`：`zstd` 为 1～22，`BZip2` 为 1～9，其余算法为 0～9。

配置只在服务器会话启动时读取，修改后需要退出并重新进入世界。无效 JSON、错误字段
类型、未知枚举或越界数值会使 DR 在本次会话中禁用；原文件会保持不变，具体原因会写入
日志。

旧版 `deathrewind.properties` 不会迁移，也不会被 Death Rewind 2.0 读取。

## 开发构建

Fabric-26.1 使用 Mojang Mapping、Fabric Loom 和 Java 25。默认仓库布局假设
DeathRewind 与 MineBackup-Mod 位于当前项目中的既定相对位置：

```powershell
cd Fabric/Fabric-26.1
./gradlew.bat clean build --warning-mode all
```

也可以显式指定 MineBackup 3.1.0 源码目录：

```powershell
./gradlew.bat clean build --warning-mode all `
  -PminebackupDir="D:\path\to\MineBackup-Mod\Fabric\Fabric-26.1"
```

生成的 Death Rewind JAR 不内置 MineBackup 实现；运行时必须同时安装 MineBackup。

仓库中的旧 Fabric、Forge 和 NeoForge 项目仅作为历史实现保留。Death Rewind 2.0 的
检查与发布流程只构建 `Fabric/Fabric-26.1`。
