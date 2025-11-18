!define APPNAME "HFD Installer"
!define OUT_EXE "dist\HFD-Installer.exe"

Unicode true
RequestExecutionLevel user
SetCompressor /SOLID lzma
SilentInstall silent
ShowInstDetails nevershow
AllowSkipFiles off

OutFile "${OUT_EXE}"
Name "${APPNAME}"

Section "Run"
  ; NSIS-managed temp directory (auto-cleaned)
  InitPluginsDir
  StrCpy $0 $PLUGINSDIR

  ; Extract payloads
  SetOutPath "$0"
  ; 1) Your trimmed JRE (must exist at project root)
  File /r "jre-win-x64\*.*"
  ; 2) Your fat JAR
  File "build\libs\HFD-Installer.jar"

  ; Launch without console
  ExecWait '"$0\bin\javaw.exe" -jar "$0\HFD-Installer.jar"'
SectionEnd
