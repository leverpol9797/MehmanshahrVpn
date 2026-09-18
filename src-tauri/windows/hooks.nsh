!macro NSIS_HOOK_POSTINSTALL
  Delete "$DESKTOP\AetherGUI.lnk"
  IfSilent desktop_shortcut_done
  Delete "$DESKTOP\Firstham AetherGui.lnk"
  MessageBox MB_YESNO|MB_ICONQUESTION "Create an Aethon shortcut on the Desktop?" IDNO desktop_shortcut_done
  ; The bundler renames the main binary to the product name, so the installed file is Aethon.exe.
  ; The legacy name is still probed so an upgrade over an older layout keeps working.
  StrCpy $R7 "$INSTDIR\Aethon.exe"
  IfFileExists "$R7" desktop_shortcut_create
  StrCpy $R7 "$INSTDIR\aether-gui.exe"
  IfFileExists "$R7" desktop_shortcut_create desktop_shortcut_done
  desktop_shortcut_create:
  CreateShortCut "$DESKTOP\Aethon.lnk" "$R7"
  desktop_shortcut_done:
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ; Remove the TUN adapter, its routes, and the DNS overrides before the files are deleted.
  ; This used to point at aether-gui.exe, which is never present under that name in $INSTDIR, so
  ; ExecWait failed silently and an uninstall could leave the virtual adapter behind.
  StrCpy $R7 "$INSTDIR\Aethon.exe"
  IfFileExists "$R7" repair_network_run
  StrCpy $R7 "$INSTDIR\aether-gui.exe"
  IfFileExists "$R7" repair_network_run repair_network_done
  repair_network_run:
  ExecWait '"$R7" --repair-network'
  Sleep 3000
  repair_network_done:
  Delete "$INSTDIR\wintun.dll"
  Delete "$DESKTOP\AetherGUI.lnk"
  Delete "$DESKTOP\Firstham AetherGui.lnk"
  Delete "$DESKTOP\Aethon.lnk"
  Delete "$SMPROGRAMS\Aethon.lnk"
  Delete "$SMPROGRAMS\AetherGUI.lnk"
  Delete "$SMPROGRAMS\Firstham AetherGui.lnk"
!macroend
