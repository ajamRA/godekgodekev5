---
name: e.MAS 5 Toolbox Desktop
description: Light green-and-white, task-based vehicle toolbox (Operate interface).
colors:
  canvas: "#f4f7f8"
  surface: "#ffffff"
  ink: "#183331"
  muted: "#566966"
  border: "#d8e3df"
  accent: "#126959"
  accent-hover: "#0b5144"
  accent-soft: "#e7f3ee"
  status-green: "#187548"
  status-warn: "#80531a"
  sidebar: "#eaf1ed"
  warning-bg: "#fcf3df"
  warning-text: "#725018"
  danger: "#a03931"
  focus: "#237bce"
  transfer-failed-bg: "#fff0eb"
  transfer-failed-text: "#8d3127"
  transfer-divider: "#b9d6c7"
  transfer-indeterminate: "#c0d7c9"
typography:
  headline:
    fontFamily: '"Segoe UI", -apple-system, BlinkMacSystemFont, Arial, sans-serif'
    fontSize: "clamp(27px, 2.7vw, 38px)"
    fontWeight: 650
    lineHeight: 1.25
    letterSpacing: "-.025em"
  title:
    fontSize: "21px"
    fontWeight: 650
    lineHeight: 1.4
    letterSpacing: "-.015em"
  subheading:
    fontSize: "16px"
    fontWeight: 650
    lineHeight: 1.5
  body:
    fontFamily: '"Segoe UI", -apple-system, BlinkMacSystemFont, Arial, sans-serif'
    fontSize: "15px"
    lineHeight: 1.6
  label:
    fontSize: "13px"
    fontWeight: 600
  supporting:
    fontSize: "12px"
  mono:
    fontFamily: "Consolas, monospace"
    fontSize: "12px"
    lineHeight: 1.6
rounded:
  field: "6px"
  button: "7px"
  notice: "8px"
  navigation: "9px"
  section: "13px"
  connection: "14px"
  chip: "30px"
spacing:
  inline: "8px"
  controls: "12px"
  field: "17px"
  mobile-gutter: "20px"
  section: "25px"
  compact-gutter: "27px"
  desktop-gutter: "42px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.surface}"
    rounded: "{rounded.button}"
    padding: "9px 17px"
  button-primary-hover:
    backgroundColor: "{colors.accent-hover}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.button}"
    padding: "9px 17px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.field}"
    padding: "9px 12px"
  section:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.section}"
    padding: "25px"
  notice:
    backgroundColor: "{colors.warning-bg}"
    textColor: "{colors.warning-text}"
    rounded: "{rounded.notice}"
    padding: "15px 18px"
---

# Design System: e.MAS 5 Toolbox Desktop

## Overview

Implemented light green-and-white Operate interface, documented from `public/style.css` and `public/index.html`. The user requested a light appearance and explicitly chose task-based navigation. This is an implementation record, not an approved visual comp or a claim of visual validation. No creative metaphor was approved.

**Key Characteristics:**
- English task guidance and familiar Android/vehicle modding terminology, with connection as the starting point.
- White working surfaces, green actions and visible amber warnings.
- Native controls and persistent form state while switching task pages.

## Colors

Primary accent identifies active navigation, primary actions, selected slots and links. Accent-soft groups selection and progress. Canvas, surface and sidebar separate the workspace without dark panels; ink and muted distinguish primary and supporting text. Amber notices and replacement text explain risk. Danger identifies potentially disruptive service actions. Connection status includes text rather than relying only on the colored bulb.

## Typography

The system uses the local Segoe UI system stack; no web font is required. Headline, title and subheading roles correspond to h1, h2 and h3. Body copy is limited to 75ch; connection guidance to 65ch. Supporting text is 12px, control text 13px and section descriptions 14px. Package identifiers and logs use Consolas; logs use line-height 1.8. Brand text is 30px/1.2 at weight 750, reducing to 25px on mobile.

## Layout

Desktop uses a fixed 238px sidebar, matching workspace offset, and a 74px-minimum topbar. Main/footer content has a 1240px maximum width and 32px 42px padding. Start-page choices use three columns; presets and APK installation use two columns. Section surfaces use 25px padding and 28px top separation. Controls have 43px minimum height; small buttons use 35px.

The desktop launcher is a frameless WebView2 host with a 6px native resize border and no OS caption. Its sole control row lives in the HTML topbar: minimize, maximize/restore and close. WebView2 non-client-region support makes the header a native draggable/double-clickable caption; links and window buttons are no-drag regions. The maximize button is hidden outside the native host. Native close and Alt+F4 call the same renderer backup confirmation, then stop only the backend process the launcher started. A backend already listening on port 5500 is shared and is never stopped or required to expose a shutdown route.

At max-width 1100px, the sidebar and offset become 205px, horizontal gutters 27px, backup actions wrap below their description/size, and steering rows stack. At max-width 760px, navigation becomes a static, horizontally scrollable top row; secondary nav descriptions disappear. Workspace offset is removed, gutters become 20px, main padding becomes 25px 20px, task/preset/install grids become single-column, sound/manual-connect rows stack and primary connection/backup actions fill available width. At min-width 1600px, main top padding becomes 42px.

## Elevation & Depth

White surfaces, pale green grouping and 1px borders provide separation without shadows. The global focus-visible indicator is a 3px blue outline with 4px offset. Existing window buttons retain their hover transition.

## Shapes

Rounded rectangular controls and surfaces use the frontmatter scale. Circular step markers and status bulbs contrast with pill-shaped connection status. The APK picker uses a dashed border; section separators use solid borders. There is no custom-painted select or modal system.

## Components

- **Navigation:** native hash links for Get Started, Apps & APKs, Vehicle Audio and Backup & Mods; active links have solid green fill, white text and aria-current. Internal hash IDs stay unchanged. Inactive pages are hidden rather than rebuilt, retaining form state during task navigation; this does not promise persistence across reloads.
- **Buttons:** native buttons with explicit English actions. Install, Start/Stop and Enable/Disable describe different operations; never label all three as installation. Secondary hover uses pale green and accent border; primary hover darkens the accent. Busy disabled buttons use opacity 0.6 and a wait cursor. Danger-soft buttons use red text and border, with pale red hover.
- **Inputs:** native labeled text fields, selects and file inputs. Visible file-picking buttons delegate to native file inputs; selected filenames remain visible. Preserve normal keyboard behavior and global focus styling.
- **Cards and notices:** white bordered sections group related operations; amber notices expose overwrite risks before actions. A shared power/connectivity warning sits immediately after the topbar, before main, and remains visible across all four tasks. It uses 12px/1.65 text, warning-text on warning-bg, and 11px 42px padding (11px 20px at max-width 760px). Keep this operational warning outside task-specific hidden sections and collapsed disclosures. Slot targets and replacement warnings remain explicit.
- **Status and disclosure:** task progress is above every page. Measured dumps show aggregate bytes, decimal MB/s and estimated time left. Unknown progress has no percentage or aria-valuenow; unknown speed and ETA say Measuring / Calculating. Terminal failure and completion stay visible until a later operation replaces them; failure uses transfer-failed colors, never a false 100% success. Disclosures contain manual IP entry, backup contents and activity logs, not safety warnings.
- **Backup lifecycle:** close protection begins as soon as any backup request is submitted, before the first status poll. Stale inactive snapshots cannot clear that protection while the request is pending. An explicitly confirmed in-app close suppresses the duplicate native unload prompt; cancelled close does not stop the backend.
- **Large partition dump:** keep a local amber warning immediately beside the action as well as the shared awake notice. Name super/userdata, free disk space, IHU/PC sleep, ADB disconnection, partial images and the lack of offline snapshot consistency. The transfer warning uses a 1px divider; 16px tabular values sit under 12px metric labels in a wrapping flex row.
- **Mod explanations:** Custom APK is slot replacement through a bind-mount overlay, not standalone sideloading. Stealth buttons remotely run an installed IHU script. Steering installation deploys the controller; Start/Stop manage the existing daemon without uninstalling.

## Do's and Don'ts

### Do:
- **Do** preserve task-based navigation, the light appearance and English modding terminology.
- **Do** retain form values when navigating between task pages.
- **Do** keep overwrite warnings, operation confirmations, real progress and activity logs visible in their intended contexts.
- **Do** retain native controls, labels and visible keyboard focus.

### Don't:
- **Don't** reintroduce a dark default theme or hide all features in one undifferentiated screen.
- **Don't** fabricate connection, vehicle data, success or backup completion.
- **Don't** interpret this record as approval of a comp, a new brand identity or completed visual validation.
