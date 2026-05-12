# Portal Routine Board Design

## Goal

Build a sideloaded Android app that turns the Meta Portal into an always-on routine board for two children. The Portal should feel like a household appliance: it wakes to the right part of the day, shows each child what to do next, lets them mark tasks complete with large touch targets, and gives parents a simple way to manage routines from another device on the home network.

## Target Device

- Device: Meta Portal, model `aloha`, Android 9, `arm64-v8a`.
- Access path: ADB over USB or Wi-Fi, with sideloading confirmed.
- Default experience: Android Home activity set through `cmd package set-home-activity`.
- Root requirement: none. The app must work with normal sideloaded app permissions.

## MVP Scope

The MVP includes:

- A full-screen Portal routine board.
- Two child columns.
- Fixed routine windows, such as Morning, After School, and Evening.
- Automatic selection of the active routine window based on local device time.
- Large tappable task cards with complete and incomplete states.
- A progress or reward indicator for each child.
- A brief celebration when both children complete the active routine window.
- Daily automatic reset plus a parent manual reset.
- A local parent admin page available only on the home network.
- Local data storage on the Portal.
- A parent escape hatch for app settings and launching maintenance tools.

The MVP does not include cloud accounts, off-network remote access, multi-household sync, voice control, camera features, or root-level Portal modifications.

## Portal Experience

The main screen is landscape-first and designed for a 1280 x 800 Portal display. It shows:

- A compact header with the active routine window, date, time, and a parent settings entry point.
- Two equally weighted child columns.
- Each column contains the child's name, progress state, reward indicator, and active-window task cards.
- Task cards are large enough for child touch input from a counter or table.
- Completed cards change visual state clearly while staying readable.
- A manual window switcher is available behind the parent control, not as a prominent child-facing feature.

The app should avoid showing the whole day by default. The default view is always "what matters now."

## Routine Windows

Routine windows are configured with fixed local start times. The app chooses the active window by comparing the current local time against the configured schedule.

Example:

- Morning starts at 6:30 AM.
- After School starts at 3:00 PM.
- Evening starts at 6:30 PM.

If the current time is before the first configured window, the app shows the earliest window for that day. If the current time is after the last configured window, the app shows the last window until the daily reset.

Parents can temporarily override the active window from the Portal settings control. The override remains active until the next routine window change, manual reset, or daily reset.

## Task Behavior

Each task belongs to one child and one routine window. A child can tap a card to toggle the task between incomplete and complete. Completion state is stored immediately so it survives app restarts.

The app records lightweight history:

- Date.
- Routine window.
- Child.
- Task id.
- Completion timestamp.
- Whether the completion was later cleared.

History exists to support simple parent review later, not analytics-heavy scoring.

## Reward And Celebration

Each child sees progress for the active window, such as completed count and a visual reward meter. The reward should be encouraging but not distracting.

When all visible tasks for both children are complete, the app shows a short celebration state. It should be brief, dismissible, and automatically return to the routine board.

## Parent Admin

The app exposes a local admin page from the Portal on the home network, for example:

`http://192.168.4.38:8080`

The exact IP may change if the router assigns the Portal a different address. The admin page should display the current URL in the Portal parent settings view.

The admin page requires a parent PIN. From the admin page, parents can:

- Edit child names.
- Add, edit, reorder, and delete tasks.
- Assign tasks to a child and routine window.
- Configure routine window start times.
- Configure daily reset time.
- Trigger manual reset for the current day.
- View recent lightweight completion history.

The admin page should be usable from a phone or laptop browser. It should not require internet access.

## Data Model

Local data is stored in a structured database on the Portal. The model should separate configuration from daily state.

Core entities:

- `Child`: id, display name, color or theme token, sort order.
- `RoutineWindow`: id, name, local start time, sort order.
- `Task`: id, child id, routine window id, title, optional note, enabled flag, sort order.
- `DailyCompletion`: local date, task id, completed flag, completed timestamp, cleared timestamp.
- `Settings`: parent PIN hash, daily reset time, admin server enabled flag, manual active-window override.

This local-first model should be compatible with future remote sync by using stable ids and explicit timestamps.

## Architecture

The app is a native Android application with:

- A Home activity for the Portal routine board.
- A parent settings surface.
- A local embedded HTTP server for admin.
- A local database layer.
- A routine engine that derives the active window and reset behavior.

The Home activity is the only child-facing surface. The admin server and settings flow are parent-facing. The routine engine owns time-window selection and reset rules so both the Portal UI and admin page use the same behavior.

Implementation stack:

- Language: Kotlin.
- UI: conventional Android Views, not Jetpack Compose, to keep Android 9 compatibility and APK behavior predictable on the Portal.
- Local database: Room over SQLite.
- Admin server: NanoHTTPD embedded in the app process.
- Admin UI: static HTML, CSS, and JavaScript served by the app, backed by JSON endpoints.
- Package name: `com.davidedicillo.portalroutine`.
- Home activity: `com.davidedicillo.portalroutine.HomeActivity`.

## Default Home Setup

The app declares a Home-capable activity using Android intent filters for `MAIN`, `HOME`, and `DEFAULT`. After sideloading, ADB sets it as the default launcher:

```bash
adb -s 192.168.4.38:5555 shell cmd package set-home-activity com.davidedicillo.portalroutine/.HomeActivity
adb -s 192.168.4.38:5555 shell input keyevent HOME
```

KISS Launcher can remain installed as a fallback. A parent-only maintenance action can launch KISS, F-Droid, or Fennec through known package names.

## Error Handling

The Portal board should remain usable if the admin server fails. The child-facing UI reads from local storage and should show the last valid configuration.

If configuration is missing or invalid, the app shows a parent setup state rather than a broken board. If the local database cannot be read, the app shows a parent-facing recovery screen with options to retry or reset local app data.

If the admin PIN is forgotten, recovery for MVP is through ADB clearing app data or reinstalling the app.

On first launch, the Portal shows a parent setup screen that requires setting the parent PIN before the child routine board is usable. This avoids exposing an unprotected admin page during initial setup.

## Security

The MVP is home-network only. The admin page binds to the local network and requires a parent PIN. It should not expose remote access, cloud sync, or public tunneling.

The parent PIN is stored as a hash, not plaintext. Admin actions that modify routines require a valid PIN session.

ADB over Wi-Fi should be disabled when not actively installing or debugging:

```bash
adb -s 192.168.4.38:5555 usb
```

## Testing

Unit tests should cover:

- Active routine window selection.
- Manual active-window override expiration.
- Daily reset behavior.
- Task completion toggling.
- Progress calculation.
- Data migration defaults.

Integration or device checks should cover:

- Sideload install on the Portal.
- Launching the Home activity.
- Setting the app as default Home.
- Task completion persistence after app restart.
- Admin page reachable from another device on the same Wi-Fi.
- Admin task edits reflected on the Portal board.

## Visual Direction

The Portal UI should be calm, high-contrast, and legible from across a room. Each child gets a distinct accent color, but the screen should not be dominated by one color family. Task cards use clear complete and incomplete states, large text, and stable dimensions so tapping a card never shifts the layout. Reward feedback should be brief and contained: progress meter, completed count, and a short celebration overlay when the active routine is complete.
