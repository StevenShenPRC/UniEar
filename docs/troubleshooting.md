# Troubleshooting

| Symptom | Check |
|---|---|
| The device list is empty | Connect the headphones in Android Bluetooth settings and grant Nearby devices access. UniEar lists headphone audio profiles, not every paired accessory. |
| Bluetooth is connected but controls are unavailable | The exact model, firmware and OS must support an adapter transport. UniEar may still show the system battery level. |
| Auto-connect offers a button instead | Android restricted background startup. Tap **Connect**, or authorize the headphones through **Associate headphones for background connection**. |
| Wi-Fi rules work only on mobile data | Grant precise location, turn on Location, and use **Use current Wi-Fi** to capture the name the phone reports. Confirm a control session is active. |
| Notification appears without a capsule | Check notification and promotion settings. The phone's firmware decides whether a Live Update is shown in the status bar. |
| Android reports a signature conflict | Install an update signed with the same key. Migrating from a debug build to the official APK may require uninstalling the old app, which clears local settings. |
