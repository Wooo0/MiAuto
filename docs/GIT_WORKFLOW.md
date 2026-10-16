# 记录约定（MiAuto 32 位车机适配）

> 目的：不再出现"现在这个包到底是哪个 SDK 编的""上次那个改法是怎么写的"这类要靠回忆的问题。
> 两条核心规则：**一个实验一条提交**、**每次构建后重跑一遍状态清单**。

---

## 1. 分支布局

| 分支 | 指向 | 含义 |
|---|---|---|
| `main` | `059b2bb` | 上游 v0.1.1，只读参照 |
| `pr10` | `865b1c7` | **PR#10 干净基线**。要做新东西一律从这里开分支 |
| `wip/softap-on-2026-10-16` | `8e8d9ed` | 我们走错的那一段（打开 SoftAP + SDK smali 手术）。**保留作记录，不要再往上加东西** |
| `ref/pr24` | `f852d59` | 上游未合并 PR#24，只读参照（32 位 FFmpeg 可用性标注） |

新开实验分支：`git checkout -b exp/<一句话描述> pr10`

命名建议：`exp/` 试验、`fix/` 修复、`wip/` 未定型的中间态。

## 2. 远程（全部指向本地目录，不需要联网）

GitHub 在公司网络下不可达，所以这里把另外三个源头做成了**本地远程**：

| 远程名 | 指向 | 能拿到什么 |
|---|---|---|
| `origin` | `github.com/PiggyBackBayonet/MiAuto` | ⚠️ 不可达，仅作配置保留，**推不上去** |
| `offline` | `../refs/upstream` | 上游全量历史（含 `pr10`、`pr24`、`v0.1.0`、`v0.1.1`） |
| `johnny` | `../refs/johnny` | gitee Johnny_Guo v0.1.7 全量代码（低端车机适配版） |

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
cd miauto_src && bash tools/state_manifest.sh
git add docs/BUILD_STATE.md && git commit -m "..."
```

`docs/BUILD_STATE.md` 会记录当时的分支/提交，以及三类 **git 记不住**的产物的 md5：

- `sdk_smali/`（4740 个 smali，在 `miauto_src` 仓库之外）
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
   --cp--> miauto_src/app/libs/icar-sdk.dex
   --./gradlew assembleGithubDebug-->  injectIccoaDex 原地追加为 classes5.dex + zipalign + 重签
```

三重验证（详见 `docs/` 下既有说明与项目记忆）：
① `audit_field_access.py` 跨类私有成员访问审计
② 反汇编回来核对寄存器
③ APK 内 `classes5.dex` 的 md5

## 6. 异地备份

`origin`（GitHub）推不上去，所以当前**没有任何异地副本**。两个选择：

- **立即可用**：`git bundle create ../MiAuto-backup-<日期>.bundle --all`
  产出单文件全量备份，可拷 U 盘 / 网盘，恢复用 `git clone <bundle> <目标目录>`
- **长期正解**：推到用户自己的 **gitee** 仓库，或单位**内网 GitLab**（这两个网络都通）。
  例：`git remote add gitee <你的仓库地址> && git push gitee --all`
