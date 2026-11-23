# BLE-Protokoll

Firmware (`ESP32_XIAO/src/main.cpp`) und App (`AndroidApp/.../TamagotchiBle.kt`) muessen diesem Dokument entsprechen. Aenderungen am Protokoll immer zuerst hier eintragen.

- **Geraetename im Advertising:** `Tamagotchi`
- **Service:** `7a6d0001-5f3c-4a8e-9b1d-2c4e6f8a0b1c`

| Characteristic | UUID | Eigenschaften | Format |
|---|---|---|---|
| Licht | `7a6d0002-5f3c-4a8e-9b1d-2c4e6f8a0b1c` | Read, Write | 1 Byte: `0x00` = aus ... `0xFF` = volle Helligkeit |
| Text | `7a6d0003-5f3c-4a8e-9b1d-2c4e6f8a0b1c` | Read, Write | ASCII, max. 16 Zeichen, wird in Zeile 1 angezeigt |
| Akkuspannung | `7a6d0004-5f3c-4a8e-9b1d-2c4e6f8a0b1c` | Read, Notify | uint16 little-endian, Akkuspannung in mV (alle 10 s) |

**Standard Battery Service** `0x180F`

| Characteristic | UUID | Eigenschaften | Format |
|---|---|---|---|
| Battery Level | `0x2A19` | Read, Notify | 1 Byte, 0-100 %. Messung alle 10 s ueber Spannungsteiler an D0, Notify bei Aenderung |

## Geplant

| Characteristic | Format |
|---|---|
| Pet-Status | noch offen (Hunger, Laune, Alter) |
