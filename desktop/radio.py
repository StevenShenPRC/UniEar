"""Desktop Bluetooth transport for UniEar. Only transport frames cross stdin/stdout."""

import asyncio
import base64
import ctypes
import os
import re
import socket
import struct
import subprocess
import sys
import threading
import uuid
import xml.etree.ElementTree as ET


def emit(*fields):
    print("\t".join(map(str, fields)), flush=True)


def encoded(text):
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def localized(english, **translations):
    return translations.get(os.environ.get("UNIEAR_LOCALE"), english)


async def paired_devices():
    if sys.platform == "win32":
        from winrt.windows.devices.bluetooth import BluetoothDevice
        from winrt.windows.devices.enumeration import DeviceInformation

        selector = BluetoothDevice.get_device_selector_from_pairing_state(True)
        for entry in await DeviceInformation.find_all_async_aqs_filter(selector):
            device = await BluetoothDevice.from_id_async(entry.id)
            if device is None:
                continue
            try:
                address = ":".join(re.findall("..", f"{device.bluetooth_address:012X}"))
                major = int(device.class_of_device.major_class)
                if major == 4 or major == 0:
                    emit("PEER", address, encoded(device.name or entry.name or ""), "", "", "1")
            finally:
                device.close()
    elif sys.platform.startswith("linux"):
        listing = await asyncio.to_thread(
            subprocess.run, ["bluetoothctl", "devices", "Paired"], capture_output=True,
            text=True, timeout=6, check=True,
        )
        for line in listing.stdout.splitlines():
            match = re.fullmatch(r"Device ([0-9A-Fa-f:]{17})\s+(.+)", line)
            if match:
                emit("PEER", match[1], encoded(match[2]), "", "", "1")


async def scan(seconds):
    from bleak import BleakScanner

    return await BleakScanner.discover(timeout=seconds, return_adv=True)


async def discover(scan_ms, include_paired):
    errors = []
    if include_paired == "1":
        try:
            await paired_devices()
        except Exception as exc:
            errors.append(str(exc))
    try:
        for device, advertisement in (await scan(int(scan_ms) / 1000)).values():
            manufacturer = ",".join(
                f"{key}:{value.hex()}" for key, value in advertisement.manufacturer_data.items()
            )
            emit("PEER", device.address, encoded(advertisement.local_name or device.name or ""),
                 ",".join(advertisement.service_uuids), manufacturer, "0")
    except Exception as exc:
        errors.append(str(exc))
    for error in errors:
        emit("WARN", encoded(error))
    emit("DONE")


def cached_battery(address):
    result = subprocess.run(
        ["bluetoothctl", "info", address], capture_output=True, text=True, timeout=5, check=True,
        env={**os.environ, "LC_ALL": "C"},
    )
    match = re.search(r"^\s*Battery Percentage:\s+0x[0-9a-fA-F]+\s+\((\d+)\)\s*$", result.stdout, re.M)
    if match and 0 <= int(match[1]) <= 100:
        emit("BATTERY", match[1])


def characteristic(client, service, target, instance, properties):
    matches = [
        ch for svc in client.services if not service or svc.uuid == service
        for ch in svc.characteristics
        if ch.uuid == target and any(prop in ch.properties for prop in properties)
    ]
    if len(matches) == 1:
        return matches[0]
    if instance:
        matches = [ch for ch in matches if ch.handle == int(instance)]
    if len(matches) != 1:
        raise OSError(localized(
            f"GATT characteristic unavailable or ambiguous: {target}",
            zh=f"GATT 特征不可用或存在歧义：{target}", ar=f"خاصية GATT غير متاحة أو ملتبسة: {target}",
            es=f"Característica GATT no disponible o ambigua: {target}",
            fr=f"Caractéristique GATT indisponible ou ambiguë : {target}",
            ru=f"Характеристика GATT недоступна или неоднозначна: {target}",
        ))
    return matches[0]


async def gatt(address, service, tx, rx, tx_id, rx_id, response, required):
    from bleak import BleakClient

    closed = asyncio.Event()
    async with BleakClient(address, disconnected_callback=lambda _: closed.set(), timeout=12) as client:
        acknowledged = response == "1"
        writer = characteristic(client, service, tx, tx_id,
                                {"write"} if acknowledged else {"write-without-response"})
        try:
            reader = characteristic(client, service, rx, rx_id, {"notify", "indicate"})
            await client.start_notify(reader, lambda _, data: emit("DATA", bytes(data).hex()))
        except Exception:
            if required == "1":
                raise
        emit("OK")

        async def write(data):
            await client.write_gatt_char(writer, data, response=acknowledged)

        await serve(write, closed.wait())


def windows_rfcomm(address, endpoint):
    winsock = ctypes.WinDLL("Ws2_32")
    winsock.socket.argtypes = (ctypes.c_int, ctypes.c_int, ctypes.c_int)
    winsock.socket.restype = ctypes.c_size_t
    winsock.connect.argtypes = (ctypes.c_size_t, ctypes.c_void_p, ctypes.c_int)
    winsock.closesocket.argtypes = (ctypes.c_size_t,)
    sock_id = winsock.socket(32, socket.SOCK_STREAM, 3)
    if sock_id == ctypes.c_size_t(-1).value:
        raise OSError(f"Winsock RFCOMM：{winsock.WSAGetLastError()}")
    try:
        service = uuid.UUID(endpoint).bytes_le if not endpoint.isdigit() else bytes(16)
        channel = int(endpoint) if endpoint.isdigit() else 0
        destination = ctypes.create_string_buffer(
            struct.pack("<HQ16sI", 32, int(address.replace(":", ""), 16), service, channel)
        )
        if winsock.connect(sock_id, destination, len(destination) - 1) != 0:
            raise OSError(f"Winsock RFCOMM：{winsock.WSAGetLastError()}")
        return socket.socket(family=32, type=socket.SOCK_STREAM, proto=3, fileno=sock_id)
    except BaseException:
        winsock.closesocket(sock_id)
        raise


def sdp_channel(address, endpoint):
    result = subprocess.run(
        ["sdptool", "browse", "--xml", address], capture_output=True, text=True,
        timeout=9, check=True,
    )
    for document in re.findall(r"<record\b[^>]*>.*?</record>", result.stdout, re.S):
        record = ET.fromstring(document)
        services = record.findall("./attribute[@id='0x0001']/sequence/uuid")
        ids = [entry.get("value", "").lower() for entry in services]
        ids = [f"0000{item[2:]:0>4}-0000-1000-8000-00805f9b34fb" if item.startswith("0x")
               else item for item in ids]
        if endpoint.lower() not in ids:
            continue
        for protocol in record.findall("./attribute[@id='0x0004']/sequence/sequence"):
            kind = protocol.find("uuid")
            number = protocol.find("uint8")
            if kind is not None and kind.get("value") == "0x0003" and number is not None:
                return int(number.attrib["value"], 0)
    raise OSError(localized(
        f"SDP could not find the RFCOMM service: {endpoint}",
        zh=f"SDP 未找到 RFCOMM 服务：{endpoint}", ar=f"لم يعثر SDP على خدمة RFCOMM: {endpoint}",
        es=f"SDP no encontró el servicio RFCOMM: {endpoint}",
        fr=f"SDP n’a pas trouvé le service RFCOMM : {endpoint}",
        ru=f"SDP не нашёл службу RFCOMM: {endpoint}",
    ))


def classic_socket(address, endpoint, secure, l2cap):
    if sys.platform == "win32" and not l2cap:
        return windows_rfcomm(address, endpoint)
    if not sys.platform.startswith("linux"):
        raise OSError(localized(
            "This OS does not expose the Classic Bluetooth control channel",
            zh="当前系统不提供此经典蓝牙控制通道", ar="لا يوفر النظام قناة تحكم Bluetooth التقليدية",
            es="El sistema no ofrece el canal de control Bluetooth clásico",
            fr="Le système n’expose pas le canal de commande Bluetooth classique",
            ru="Система не предоставляет канал управления классического Bluetooth",
        ))
    protocol = socket.BTPROTO_L2CAP if l2cap else socket.BTPROTO_RFCOMM
    kind = socket.SOCK_SEQPACKET if l2cap else socket.SOCK_STREAM
    channel = int(endpoint) if endpoint.isdigit() else sdp_channel(address, endpoint)
    link = socket.socket(socket.AF_BLUETOOTH, kind, protocol)
    try:
        if secure == "1":
            link.setsockopt(274, 4, struct.pack("BB", 2, 0))  # SOL_BLUETOOTH / BT_SECURITY
        link.settimeout(12)
        link.connect((address, channel))
        link.settimeout(None)
        return link
    except BaseException:
        link.close()
        raise


async def classic(address, endpoint, secure, l2cap=False):
    link = await asyncio.to_thread(classic_socket, address, endpoint, secure, l2cap)
    try:
        emit("OK")

        async def read():
            while data := await asyncio.to_thread(link.recv, 4096):
                emit("DATA", data.hex())

        async def write(data):
            await asyncio.to_thread(link.sendall, data)

        await serve(write, read())
    finally:
        try:
            link.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        link.close()


async def serve(write, disconnect):
    loop = asyncio.get_running_loop()
    inputs = asyncio.Queue()

    def read_input():
        for line in sys.stdin:
            loop.call_soon_threadsafe(inputs.put_nowait, line.strip())
        loop.call_soon_threadsafe(inputs.put_nowait, "")

    threading.Thread(target=read_input, daemon=True).start()

    async def write_commands():
        while line := await inputs.get():
            verb, _, payload = line.partition("\t")
            if verb != "WRITE":
                return
            await write(bytes.fromhex(payload))
            emit("WROTE")

    jobs = [asyncio.create_task(write_commands()), asyncio.create_task(disconnect)]
    try:
        done, _ = await asyncio.wait(jobs, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            task.result()
    finally:
        for task in jobs:
            task.cancel()
        await asyncio.gather(*jobs, return_exceptions=True)


async def main(command, *args):
    if command == "discover":
        await discover(*args)
    elif command == "battery":
        cached_battery(*args)
    elif command == "gatt":
        await gatt(*args)
    elif command in {"rfcomm", "l2cap"}:
        await classic(*args, l2cap=command == "l2cap")
    else:
        raise ValueError(localized(
            "Unknown Bluetooth operation", zh="未知蓝牙操作", ar="عملية Bluetooth غير معروفة",
            es="Operación Bluetooth desconocida", fr="Opération Bluetooth inconnue", ru="Неизвестная операция Bluetooth",
        ))


if __name__ == "__main__":
    try:
        asyncio.run(main(*sys.argv[1:]))
    except Exception as error:
        emit("ERR", encoded(str(error)))
        sys.exit(1)
