# Steering controller changes

Build and run the laptop tests with `powershell -File D:\apps\emas-ota\build_steering_mode.ps1`.
The build runs the seven existing suites and TestSteeringHardening, builds Java 8
bytecode, then creates API 29 DEX. Only successful builds replace bin/classes.dex
and bin/steering_mode_controller.jar. No deployment is performed.

The controller now protects query and polling snapshots from newer telemetry;
VHAL I/O stays outside the input monitor. Original key code, device/scan/source,
meta state, repeat and flags travel through KeyMetadata and OEM dispatch. Failed
or unavailable reads invalidate readiness; the daemon retries service connection
from read/poll with a 500ms backoff. Commands are never automatically retried.

The previous evdev reader was removed: merely reading /dev/input does not consume
events, and unselected evdev timestamps need not use Android uptime. The default
daemon starts in MONITOR ONLY mode and disables vehicle writes. OEM controls are
left untouched. This is deliberate: the repository does not supply a verified OEM
interception implementation. An adapter must implement SteeringInputSession and
be supplied with `--input-adapter=qualified.ClassName`. It is not enough to return
true from start without real interception.

Adapter requirements:

- Own only NEXT/PREV from the identified steering device; let microphone and all
  unrelated events continue to the factory handler.
- Supply Android KeyEvent uptime timestamps and original metadata. Map vendor
  keys to controller's canonical codes without changing the original OEM code.
- If Sink returns true, withhold the original. The controller alone replays OEM
  single clicks and hold/repeat/release. Forwarded events must bypass the adapter
  so replay cannot trigger another gesture.
- On rejection, error, close or process death, promptly release interception and
  reconcile any held keys with OEM. Do not call Sink synchronously inside start.
- Verify menu/reboot and no duplicate media actions on the actual IHU before use.

The laptop regression suite tests this boundary using an interceptor mock; it does
not certify a real adapter, property mapping, Binder permissions or vehicle OEM
behavior. No kernel helper, global InputFilter takeover or guessed hardware grab
is silently installed by this package.
