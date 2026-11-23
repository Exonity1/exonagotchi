#include <Arduino.h>
#include <LiquidCrystal.h>
#include <NimBLEDevice.h>

// ---------- Hardware ----------
// Pinbelegung:  RS  E   D4  D5  D6  D7
LiquidCrystal lcd(D1, D2, D3, D4, D5, D10);
const int BACKLIGHT   = D6;   // ueber BC547 an Display-Pin 16
const int BATTERY_PIN = D0;   // Mitte des Spannungsteilers (2x 100 kOhm zwischen Akku+ und GND)

// Backlight-PWM ueber dem Hoerbereich, sonst fiept der Step-up
const uint32_t PWM_FREQ = 25000;
const uint8_t  PWM_BITS = 8;

// ---------- BLE-Protokoll (siehe PROTOCOL.md) ----------
#define SERVICE_UUID   "7a6d0001-5f3c-4a8e-9b1d-2c4e6f8a0b1c"
#define LIGHT_UUID     "7a6d0002-5f3c-4a8e-9b1d-2c4e6f8a0b1c"  // 1 Byte: 0 = aus ... 255 = voll
#define TEXT_UUID      "7a6d0003-5f3c-4a8e-9b1d-2c4e6f8a0b1c"  // Text, max. 16 Zeichen -> Zeile 1
#define VOLTAGE_UUID   "7a6d0004-5f3c-4a8e-9b1d-2c4e6f8a0b1c"  // uint16 little-endian: Akkuspannung in mV
#define BATTERY_SVC    "180F"  // Standard Battery Service
#define BATTERY_LEVEL  "2A19"  // Standard Battery Level, 1 Byte in Prozent

// ---------- Zustand (wird im BLE-Task gesetzt, im loop() angezeigt) ----------
portMUX_TYPE stateMux = portMUX_INITIALIZER_UNLOCKED;
volatile bool    connected    = false;
volatile uint8_t brightness   = 255;
char             text[17]     = "Hallo Welt!";
volatile bool    textChanged  = true;
volatile bool    lightChanged = true;
volatile bool    statusChanged = true;

// ---------- Akku ----------
uint32_t batteryMv  = 0;      // geglaettete Akkuspannung in mV
uint8_t  batteryPct = 0;
NimBLECharacteristic* batteryChr = nullptr;
NimBLECharacteristic* voltageChr = nullptr;

// Mittelwert aus 16 Messungen, Teiler 1:2 rueckgaengig machen
uint32_t readBatteryMv() {
  uint32_t sum = 0;
  for (int i = 0; i < 16; i++) sum += analogReadMilliVolts(BATTERY_PIN);
  return (sum / 16) * 2;
}

// Typische LiPo-Entladekurve (nicht linear!)
struct CurvePoint { uint16_t mv; uint8_t pct; };
const CurvePoint CURVE[] = {
  {4200, 100}, {4000, 85}, {3850, 65}, {3750, 45},
  {3650, 20},  {3500, 8},  {3300, 0},
};

uint8_t batteryPercent(uint32_t mv) {
  const size_t n = sizeof(CURVE) / sizeof(CURVE[0]);
  if (mv >= CURVE[0].mv) return 100;
  for (size_t i = 1; i < n; i++) {
    if (mv >= CURVE[i].mv) {
      const CurvePoint& hi = CURVE[i - 1];
      const CurvePoint& lo = CURVE[i];
      // Zwischen zwei Tabellenpunkten linear interpolieren
      return lo.pct + (mv - lo.mv) * (hi.pct - lo.pct) / (hi.mv - lo.mv);
    }
  }
  return 0;
}

void updateBattery() {
  uint32_t mv = readBatteryMv();
  // Glaetten, damit die Anzeige bei Lastspitzen (Licht, BLE) nicht springt
  batteryMv = (batteryMv == 0) ? mv : (batteryMv * 3 + mv) / 4;

  uint8_t pct = batteryPercent(batteryMv);
  if (pct != batteryPct) {
    batteryPct = pct;
    statusChanged = true;
    if (batteryChr) {
      batteryChr->setValue(pct);
      if (connected) batteryChr->notify();
    }
  }
  if (voltageChr) {
    voltageChr->setValue((uint16_t)batteryMv);
    if (connected) voltageChr->notify();
  }
  Serial.printf("Akku: %lu mV (%u %%)\n", (unsigned long)batteryMv, batteryPct);
}

// ---------- BLE-Callbacks ----------
class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& info) override {
    connected = true;
    statusChanged = true;
    Serial.printf("Handy verbunden: %s\n", info.getAddress().toString().c_str());
  }
  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& info, int reason) override {
    connected = false;
    statusChanged = true;
    Serial.printf("Verbindung getrennt (Grund %d), werbe wieder...\n", reason);
    NimBLEDevice::startAdvertising();
  }
};

class LightCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* chr, NimBLEConnInfo& info) override {
    NimBLEAttValue v = chr->getValue();
    if (v.size() < 1) return;
    brightness = v.data()[0];
    lightChanged = true;
    Serial.printf("Licht -> %u\n", brightness);
  }
};

class TextCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* chr, NimBLEConnInfo& info) override {
    NimBLEAttValue v = chr->getValue();
    size_t n = v.size() > 16 ? 16 : v.size();
    portENTER_CRITICAL(&stateMux);
    memcpy(text, v.data(), n);
    text[n] = '\0';
    textChanged = true;
    portEXIT_CRITICAL(&stateMux);
    Serial.printf("Text -> \"%s\"\n", text);
  }
};

void setupBle() {
  NimBLEDevice::init("Tamagotchi");
  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  // Eigener Tamagotchi-Service
  NimBLEService* service = server->createService(SERVICE_UUID);

  NimBLECharacteristic* lightChr = service->createCharacteristic(
      LIGHT_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
  lightChr->setValue((uint8_t)brightness);
  lightChr->setCallbacks(new LightCallbacks());

  NimBLECharacteristic* textChr = service->createCharacteristic(
      TEXT_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
  textChr->setValue(text);
  textChr->setCallbacks(new TextCallbacks());

  voltageChr = service->createCharacteristic(
      VOLTAGE_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  voltageChr->setValue((uint16_t)batteryMv);

  service->start();

  // Standard Battery Service
  NimBLEService* batService = server->createService(BATTERY_SVC);
  batteryChr = batService->createCharacteristic(
      BATTERY_LEVEL, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  batteryChr->setValue(batteryPct);
  batService->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->setName("Tamagotchi");
  adv->start();
  Serial.println("BLE bereit, warte auf Handy...");
}

// Zeile 2: Verbindungsstatus und Akkustand, z. B. "BLE ok Akku  78%"
void drawStatus() {
  char line[17];
  snprintf(line, sizeof(line), "%s Akku%4u%%", connected ? "BLE ok" : "BLE --", batteryPct);
  lcd.setCursor(0, 1);
  lcd.printf("%-16s", line);
}

// ---------- Setup / Loop ----------
void setup() {
  Serial.begin(115200);

  ledcAttach(BACKLIGHT, PWM_FREQ, PWM_BITS);
  ledcWrite(BACKLIGHT, brightness);

  delay(200);          // Display nach dem Einschalten hochfahren lassen
  lcd.begin(16, 2);
  lcd.clear();

  updateBattery();     // erster Messwert, bevor BLE startet
  setupBle();
}

void loop() {
  // Selbstheilung: Display alle 5 s neu initialisieren und alles neu zeichnen.
  // Falls ein Reset oder Stoerungen das Display aus dem Takt bringen, ist es danach wieder sauber.
  static uint32_t lastRefresh = 0;
  if (millis() - lastRefresh >= 5000) {
    lastRefresh = millis();
    lcd.begin(16, 2);
    textChanged = true;
    statusChanged = true;
  }

  // Akku alle 10 s messen
  static uint32_t lastBattery = 0;
  if (millis() - lastBattery >= 10000) {
    lastBattery = millis();
    updateBattery();
  }

  // Displayzugriffe nur hier im loop(), nie direkt aus den BLE-Callbacks
  if (lightChanged) {
    lightChanged = false;
    ledcWrite(BACKLIGHT, brightness);
  }

  if (textChanged) {
    char line[17];
    portENTER_CRITICAL(&stateMux);
    textChanged = false;
    memcpy(line, text, sizeof(line));
    portEXIT_CRITICAL(&stateMux);

    lcd.setCursor(0, 0);
    lcd.printf("%-16s", line);   // mit Leerzeichen auffuellen, alter Text verschwindet
  }

  if (statusChanged) {
    statusChanged = false;
    drawStatus();
  }

  delay(20);
}
