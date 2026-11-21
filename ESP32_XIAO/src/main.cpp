#include <Arduino.h>
#include <LiquidCrystal.h>
#include <NimBLEDevice.h>

// ---------- Hardware ----------
// Pinbelegung:  RS  E   D4  D5  D6  D7
LiquidCrystal lcd(D1, D2, D3, D4, D5, D10);
const int BACKLIGHT = D6;   // ueber BC547 an Display-Pin 16

// ---------- BLE-Protokoll ----------
// Eigene UUIDs fuer den Tamagotchi-Service
#define SERVICE_UUID    "7a6d0001-5f3c-4a8e-9b1d-2c4e6f8a0b1c"
#define LIGHT_UUID      "7a6d0002-5f3c-4a8e-9b1d-2c4e6f8a0b1c"  // 1 Byte: 0 = aus ... 255 = voll
#define TEXT_UUID       "7a6d0003-5f3c-4a8e-9b1d-2c4e6f8a0b1c"  // Text, max. 16 Zeichen -> Zeile 1

// ---------- Zustand (wird im BLE-Task gesetzt, im loop() angezeigt) ----------
portMUX_TYPE stateMux = portMUX_INITIALIZER_UNLOCKED;
volatile bool    connected   = false;
volatile uint8_t brightness  = 255;
char             text[17]    = "Hallo Welt!";
volatile bool    textChanged = true;
volatile bool    lightChanged = true;
volatile bool    connChanged  = true;

// ---------- BLE-Callbacks ----------
class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& info) override {
    connected = true;
    connChanged = true;
    Serial.printf("Handy verbunden: %s\n", info.getAddress().toString().c_str());
  }
  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& info, int reason) override {
    connected = false;
    connChanged = true;
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

NimBLECharacteristic* lightChr;

void setupBle() {
  NimBLEDevice::init("Tamagotchi");
  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  NimBLEService* service = server->createService(SERVICE_UUID);

  lightChr = service->createCharacteristic(
      LIGHT_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
  lightChr->setValue((uint8_t)brightness);
  lightChr->setCallbacks(new LightCallbacks());

  NimBLECharacteristic* textChr = service->createCharacteristic(
      TEXT_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
  textChr->setValue(text);
  textChr->setCallbacks(new TextCallbacks());

  service->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->setName("Tamagotchi");
  adv->start();
  Serial.println("BLE bereit, warte auf Handy...");
}

// ---------- Setup / Loop ----------
void setup() {
  Serial.begin(115200);
  pinMode(BACKLIGHT, OUTPUT);
  analogWrite(BACKLIGHT, brightness);

  delay(200);          // Display nach dem Einschalten hochfahren lassen
  lcd.begin(16, 2);
  lcd.clear();

  setupBle();
}

void loop() {
  // Selbstheilung: Display alle 5 s neu initialisieren und alles neu zeichnen.
  // Falls Stoerungen das Display aus dem Takt bringen, ist es danach wieder sauber.
  static uint32_t lastRefresh = 0;
  if (millis() - lastRefresh >= 5000) {
    lastRefresh = millis();
    lcd.begin(16, 2);
    textChanged = true;
    connChanged = true;
  }

  // Displayzugriffe nur hier im loop(), nie direkt aus den BLE-Callbacks
  if (lightChanged) {
    lightChanged = false;
    analogWrite(BACKLIGHT, brightness);
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

  if (connChanged) {
    connChanged = false;
    lcd.setCursor(0, 1);
    lcd.print(connected ? "BLE: verbunden  " : "BLE: wartet...  ");
  }

  delay(20);
}
