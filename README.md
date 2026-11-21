# Exonagotchi

Ein selbstgebautes Tamagotchi im Y2K-Look: ein klassisches 16x2-LCD, ein ESP32 und eine eigene Android-App, verbunden ueber Bluetooth Low Energy.

> Status: Prototyp auf dem Breadboard. Display, Backlight-Dimmung und BLE-Steuerung per App laufen.

## Aufbau

| Ordner | Inhalt |
|---|---|
| [`ESP32_XIAO/`](ESP32_XIAO) | Firmware fuer den Seeed XIAO ESP32-C3 (PlatformIO, Arduino, NimBLE) |
| [`AndroidApp/`](AndroidApp) | Android-App (Kotlin, Jetpack Compose) |
| [`PROTOCOL.md`](PROTOCOL.md) | BLE-Protokoll zwischen Firmware und App |

## Hardware

- Seeed Studio XIAO ESP32-C3
- LCD 1602 (HD44780-kompatibel), 4-Bit-Modus
- BC547 + 1 kOhm fuer das dimmbare Backlight
- LiPo 3,7 V / 1000 mAh, 5-V-Step-up fuer das Display (in Arbeit)

### Verdrahtung

| LCD-Pin | Funktion | XIAO / Ziel |
|---|---|---|
| 1 | VSS | GND |
| 2 | VDD | 5 V |
| 3 | V0 (Kontrast) | GND (ggf. ueber ~1 kOhm) |
| 4 | RS | D1 |
| 5 | RW | GND |
| 6 | E | D2 |
| 11 | D4 | D3 |
| 12 | D5 | D4 |
| 13 | D6 | D5 |
| 14 | D7 | D10 |
| 15 | A (Backlight +) | 5 V |
| 16 | K (Backlight -) | Collector BC547 (Basis ueber 1 kOhm an D6, Emitter an GND) |

## Loslegen

**Firmware:** `ESP32_XIAO` in VS Code mit PlatformIO oeffnen, XIAO per USB-C anschliessen, Upload.

**App:** `AndroidApp` in Android Studio oeffnen, Gradle-Sync, auf ein Handy mit Android 16 installieren. Die App sucht nach dem Geraet `Tamagotchi`.

## Roadmap

- [x] Display + dimmbares Backlight
- [x] BLE-Steuerung per Android-App
- [ ] Akkubetrieb mit Step-up und Akkustand-Anzeige
- [ ] Pet: Pixel-Figur, Animationen, Hunger und Laune
- [ ] Gehaeuse (3D-Druck, transparent blau)
- [ ] Eigenes PCB als Display-Rucksack
