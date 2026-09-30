; ─────────────────────────────────────────────────────────────
; Aye NSIS 自定义钩子
;
; 要解决的问题：托盘驻留 + 内置 java sidecar 场景下，升级/卸载时
;   1) Aye.exe 可能仍驻留托盘，旧版 resources\backend\server.jar 被锁；
;   2) Electron 进程树即使被 /T 杀，java.exe 也可能因树断裂而残留。
;
; 注意：electron-builder 模板中 customRemoveFiles 一旦定义就完全接管文件删除，
; 因此卸载宏里必须复刻默认的“原子改名 → RMDir”删除逻辑。
; ─────────────────────────────────────────────────────────────

!macro AyeKillResidual
  ; 1. 结束应用本体及其进程树（内置 java 是其子进程，/T 通常一并结束）
  nsExec::Exec 'taskkill /F /IM ${APP_EXECUTABLE_FILENAME} /T'
  Pop $0
  ; 2. 兜底：仅结束 Aye 内置 runtime 中、命令行加载 server.jar 的 java
  ;    （NSIS 中 $$ 转义为 PowerShell 的 $_；'' 为字符串内的单引号）
  nsExec::Exec 'powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process -Filter \"Name=''java.exe''\" | Where-Object { $$_.CommandLine -like ''*server.jar*'' -and $$_.ExecutablePath -like ''*\resources\runtime\*'' } | ForEach-Object { Stop-Process -Id $$_.ProcessId -Force -ErrorAction SilentlyContinue }"'
  Pop $0
  ; 等待杀软/系统释放文件句柄，避免 server.jar 删除/覆盖失败
  Sleep 800
!macroend

; 安装/覆盖升级初始化时（在卸载旧版本与解包之前）
!macro customInit
  !insertmacro AyeKillResidual
!macroend

; 卸载时（在删除安装文件之前；本宏同时接管默认文件删除流程）
!macro customRemoveFiles
  !insertmacro AyeKillResidual

  ${if} ${isUpdated}
    CreateDirectory "$PLUGINSDIR\old-install"

    Push ""
    Call un.atomicRMDir
    Pop $R0

    ${if} $R0 != 0
      DetailPrint "File is busy, aborting: $R0"

      Push ""
      Call un.restoreFiles
      Pop $R0

      Abort `Can't rename "$INSTDIR" to "$PLUGINSDIR\old-install".`
    ${endif}

  ${endif}

  # Remove all files (or remaining shallow directories from the block above)
  # /REBOOTOK：Explorer 短暂占用根目录时挂到重启后清理，避免留下空壳目录
  RMDir /r /REBOOTOK "$INSTDIR"
  ; 卸载器启动器自身位于 $INSTDIR 内，它要等本 Section 跑完才退出，
  ; 因此这里异步分离一个 cmd：先等启动器退出，再重试删除空根目录（Exec 不等待子进程）
  ExecShell "" '"$SYSDIR\cmd.exe"' '/c (cd /d "%TEMP%" & for /l %i in (1,1,15) do @(ping -n 2 127.0.0.1 >nul & rd /s /q "$INSTDIR" & if not exist "$INSTDIR" exit /b 0))' SW_HIDE
!macroend
