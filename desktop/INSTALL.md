# UniEar Desktop

Extract the archive before launching. Java is bundled. Install Python 3.12, then use that interpreter to install the Bluetooth helper dependencies:

```sh
python -m pip install -r path/to/radio/requirements.txt
```

| Platform | Launcher | Bluetooth support |
| --- | --- | --- |
| Windows 11 x64 | `UniEar/UniEar.exe` | BLE and Classic RFCOMM. Dependencies are in `UniEar/app/radio/requirements.txt`. |
| Linux x64 | `UniEar/bin/UniEar` | BLE and Classic RFCOMM/L2CAP via BlueZ. Install `bluetoothctl` and enable the Bluetooth service. The optional `sdptool` command is required for discovery of Classic services by UUID. Dependencies are in `UniEar/lib/app/radio/requirements.txt`. |
| macOS Intel and Apple silicon | `UniEar.app` | BLE/GATT. Grant Bluetooth permission when prompted. Dependencies are in `UniEar.app/Contents/app/radio/requirements.txt`. The bundle is not notarized. |

On Linux and macOS use `python3` if that is the Python command. Set `UNIEAR_PYTHON` to the absolute interpreter path if the launcher does not inherit the intended `PATH`, such as when starting from Finder. Adapter commands depend on the exact headphone model and the transport available on the host.

Use **Language** to choose one of the six supported languages or follow the OS language. The preference is saved on this computer.
