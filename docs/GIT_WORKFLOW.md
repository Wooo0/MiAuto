# 记录约定（MiAuto 32 位车机适配）

> 目的：不再出现"现在这个包到底是哪个 SDK 编的""上次那个改法是怎么写的"这类要靠回忆的问题。
> 两条核心规则：**一个实验一条提交**、**每次构建后重跑一遍状态清单**。

---

## 1. 分支布局

| 分支 | 指向 | 含义 |
|---|---|---|
| `main` | `059b2bb` | 上游 v0.1.1，只读参照 |
| `pr10` | `865b1c7` | **PR#10 干净基线**。要做新东西一律从这里开分支 |
| `wip/softap-on-2026-10-16` | `9fb2304` | 我们走错的那一段（打开 SoftAP + SDK smali 手术）。**保留作记录，不要再往上加东西** |
| `ref/pr24` | `f852d59` | 上游未合并 PR#24，只读参照（32 位 FFmpeg 可用性标注） |
| **`ours/32bit-headunit`** | `9fb2304` | **我们的版本**。32 位车机适配的主工作分支，后续改动都往这里加 |

新开实验分支：`git checkout -b exp/<一句话描述> pr10`

命名建议：`exp/` 试验、`fix/` 修复、`wip/` 未定型的中间态。

## 2. 远程（全部指向本地目录，不需要联网）

GitHub 在公司网络下不可达，所以这里把另外三个源头做成了**本地远程**：

| 远程名 | 指向 | 能拿到什么 |
|---|---|---|
| **`ours`** | `git@github.com:Wooo0/MiAuto.git` | **我们自己的仓库**（异地备份目标）。见下方认证状态 |
| `origin` | `github.com/PiggyBackBayonet/MiAuto` | 上游原仓库，只读参照（推送无意义，我们没有写权限） |
| `offline` | `../refs/upstream` | 上游全量历史（含 `pr10`、`pr24`、`v0.1.0`、`v0.1.1`） |
| `johnny` | `../refs/johnny` | gitee Johnny_Guo v0.1.7 全量代码（低端车机适配版） |

### `ours` 的认证状态（2026-10-16 19:25 已打通）

本机 `~/.ssh/config` 已把 `github.com` 映射到 `ssh.github.com:443`
（**这条映射很关键**，直连 22 端口不通），公钥已注册到 `Wooo0` 账号，认证**通过**：

```bash
$ ssh -T git@github.com
Hi Wooo0! You've successfully authenticated, but GitHub does not provide shell access.
```

本机密钥：`~/.ssh/id_ed25519` → `SHA256:ouGrey26vVQJKIYpFBj4hcNjBgsegqOTlre8DA4Khxs`

### 推送（2026-10-16 20:10 全部完成）

远端现在有：

| 引用 | 提交 | 说明 |
|---|---|---|
| `refs/heads/ours/32bit-headunit` | `fe0bcbb` | **我们的版本**（主工作分支） |
| `refs/heads/pr10` | `865b1c7` | PR#10 干净基线（存档） |
| `refs/heads/ref/pr24` | `f852d59` | 上游 PR#24（存档） |
| `refs/heads/wip/softap-on-2026-10-16` | `9fb2304` | 走错那一段（存档） |
| `refs/heads/main` | `059b2bb` | 上游 v0.1.1 |
| `refs/tags/v0.1.0` · `refs/tags/v0.1.1` | `7501c66` · `059b2bb` | 上游标签 |

日常推送：

```bash
cd <项目路径>/miauto
git push             # upstream 已设好，直接推当前分支
git push ours --all  # 把所有本地分支一次性同步上去
```

> ⚠️ **不要用 `--tags`。** 本仓库的 `v0.1.7` 标签指向 Johnny 的提交 `ddc7662`，
> 推它会把 Johnny 的整段历史（含他 `releases/*.apk`）一起搬过去 —— 实测
> **2824 个对象 / 290.6 MB 未压缩**；而 `--all` 只推分支只要 59 个对象 / 2.1 MB。
> 需要标签就显式挑（`v0.1.0`/`v0.1.1` 已推，成本≈0）。

### 怎么验证推送真的成功了（重要）

**绝不看退出码。** 用下面两条，任一条即可：

```bash
git ls-remote --heads https://github.com/Wooo0/MiAuto.git   # ← 首选，走 https，随时可用
git config --get branch.ours/32bit-headunit.remote          # ← 本地信号，push 成功才会写入
```

原因：本机曾在**同一把已注册的 SSH 密钥下**出现过 `git push` / `git ls-remote`
返回**退出码 0 且 stdout+stderr 全空、`GIT_TRACE=1` 连 trace 都不打印**的现象
（"看起来成功、实际什么都没做"），而同一时刻直连 `ssh` 与 https 都正常。
该现象**后来不再复现，原因未能确定**，所以不排除是间歇性的。
**结论：推送后一律用上面两条核实，不要凭退出码或"没报错"下判断。**

> ⚠️ **本机默认终端是 Windows PowerShell 5.1，不支持 `&&`**
> （报 `标记"&&"不是此版本中的有效语句分隔符`）。多条命令用 `;` 分隔，或一条一条跑。

于是"看别人改了什么"不再靠翻文件，而是：

```bash
git log --oneline offline/remotes/origin/pr24        # PR#24 的提交
git diff main..ref/pr24 --stat                       # PR#24 改了什么
git diff main..johnny/master --stat -- '*.java'      # Johnny 改了什么（注意它挪到了 source/ 目录）
```

## 3. 一个实验 = 一条提交

提交信息固定写三段，缺一不可：

```
<一句话：这次想验证什么>

假设：<为什么认为这么做能解决问题，依据是什么>
做法：<具体改了哪里，文件:行号>
结果：<实测现象 / 日志关键行 / 结论 —— 成功失败都写>
```

**不要**把所有改动攒成一个大提交。攒一起的代价我们已经付过了：
27 个文件一次性堆在工作区两天，既分不清哪几行是为软 AP 服务的、也无法单独回退任何一项。

## 4. 每次构建后：重跑状态清单

```bash
cd miauto && bash tools/state_manifest.sh
git add docs/BUILD_STATE.md && git commit -m "..."
```

`docs/BUILD_STATE.md` 会记录当时的分支/提交，以及三类 **git 记不住**的产物的 md5：

- `sdk_smali/`（4740 个 smali，在 `miauto` 仓库之外）
- `*.dex`（`.gitignore` 排除）—— 含 PR#10 原版、补丁版、构建实际挂载版三方对照
- `output/*.apk`（`.gitignore` 排除）

它的最后会给出一句判定，例如：

> **判定：当前构建使用的 SDK = 我们动过刀的那份（与 PR#10 原版不一致）**

**为什么要靠 md5 而不是文件大小**：修 bug 前后打进 APK 的 `classes5.dex` 大小都恰好
是 `7084160` B，看大小会得出"没改成功"的错误结论。**核对一律以 md5 为准。**

## 5. 改 SDK 时的额外要求

SDK 走的是这条链，任何一环换掉都要在提交信息里写明前后 md5：

```
sdk_smali/*.smali  --java -cp "smali_tools/*" org.jf.smali.Main assemble-->  icar-sdk-patched.dex
   --cp--> miauto/app/libs/icar-sdk.dex
   --./gradlew assembleGithubDebug-->  injectIccoaDex 原地追加为 classes5.dex + zipalign + 重签
```

三重验证（详见 `docs/` 下既有说明与项目记忆）：
① `audit_field_access.py` 跨类私有成员访问审计
② 反汇编回来核对寄存器
③ APK 内 `classes5.dex` 的 md5

## 6. 异地备份

两条路，互为兜底：

- **立即可用（不依赖任何账号）**：`git bundle`
  ```bash
  git bundle create ../MiAuto-ours-<日期>.bundle \
      refs/heads/main refs/heads/pr10 refs/heads/ref/pr24 refs/heads/ours/32bit-headunit
  ```
  产出**单文件全量备份**，可拷 U 盘 / 网盘；恢复：`git clone <bundle> <目标目录>`。
  2026-10-16 实测：9.66 MB，克隆恢复后 878 个文件、历史完整。
- **长期正解**：推到我们自己的 `ours`（`git@github.com:Wooo0/MiAuto.git`）。
  网络路径已通，**只差把公钥注册到 GitHub** —— 见第 2 节的认证状态。
