# e.MAS 5 Control Center

<!-- impeccable:product-schema 1 -->

## Platform

web

## Product Purpose

Portable Windows toolbox for managing the Proton e.MAS 5 infotainment unit through a local Node.js server and bundled ADB. Existing features include APK slot installation, application presets, lock and AVAS audio, steering controls, backups, stealth mode, reboot and screenshots.

## Operating Context

The laptop and car must share a Wi-Fi network. The launcher opens http://127.0.0.1:5500 in a frameless WinForms/WebView2 desktop window, initially 1280 × 820. The user requested removing the duplicate upper Windows/browser title bar; the HTML header supplies the sole minimize/maximize/close controls, native drag and resize remain available. Requires Windows x64, .NET Framework 4.6.2+ and the current Microsoft Edge WebView2 Runtime. No vehicle connection is required to browse the interface. Vehicle operations require a real connection. The window stops only a backend process it launched; a server already listening on port 5500 remains running after the window closes.

## Confirmed Redesign Requirements

The user finds the current screen confusing and too dark. Use a light theme and explain where to start. The user chose task-based navigation: start/connection, applications, audio, and backup/service. Preserve existing capabilities and use English throughout the UI, including confirmations, validation, progress and server-authored results. Use familiar Android/vehicle modding terms and clearly distinguish installation from remote start/stop or mode toggles.

## Capabilities and Constraints

Custom APK installation uploads an APK, overlays a selected app slot using a bind mount, then invokes Android Package Manager; it is not a standalone sideload. Popular presets overlay TikTok or JOOX slots. Stealth buttons remotely invoke a service-mode script already on the IHU, while the steering installer deploys a controller; Start/Stop only manage the existing daemon. Keep overwrite warnings and operation confirmations. The large eMMC action dumps super and userdata partition images, not the whole raw eMMC; warn prominently about free PC disk space, Wi-Fi ADB disconnection, IHU/PC sleep, and live-data consistency. Show measured bytes, transfer rate and ETA when available, never invented progress or backup success.

## Evidence on Hand

public/index.html, public/app.js, server.js, Launcher.cs and CARA_GUNA.txt describe the existing capabilities. The incumbent interface was inspected in the running local application before redesign.
