#include <Arduino.h>
#include <LiquidCrystal.h>

// 1 = Kabeltest (jeder Datenpin nacheinander HIGH), 0 = normaler Display-Test
#define PIN_TEST 0

// Pinbelegung:  RS  E   D4  D5  D6  D7
LiquidCrystal lcd(D1, D2, D3, D4, D5, D10);
const int BACKLIGHT = D6;   // erst ab dem Transistor-Schritt genutzt

#if PIN_TEST
struct TestPin { int pin; const char* xiao; const char* display; };
TestPin testPins[] = {
  {D1,  "D1",  "Pin 4 (RS, gelb)"},
  {D2,  "D2",  "Pin 6 (E, blau)"},
  {D3,  "D3",  "Pin 11 (D4, weiss)"},
  {D4,  "D4",  "Pin 12 (D5, lila)"},
  {D5,  "D5",  "Pin 13 (D6, grau)"},
  {D10, "D10", "Pin 14 (D7, lachs)"},
};

void setup() {
  Serial.begin(115200);
  for (auto& t : testPins) { pinMode(t.pin, OUTPUT); digitalWrite(t.pin, LOW); }
}

void loop() {
  for (auto& t : testPins) {
    for (auto& o : testPins) digitalWrite(o.pin, LOW);
    digitalWrite(t.pin, HIGH);
    Serial.printf("\nJetzt HIGH: XIAO %s -> 3,3 V sollten NUR an Display-%s messbar sein\n",
                  t.xiao, t.display);
    delay(4000);
  }
}
#else
void setup() {
  Serial.begin(115200);
  pinMode(BACKLIGHT, OUTPUT);
  digitalWrite(BACKLIGHT, HIGH);

  delay(200);          // Display nach dem Einschalten hochfahren lassen
  lcd.begin(16, 2);
  lcd.clear();
  lcd.print("Hallo Welt!");
  Serial.println("Display initialisiert");
}

// Backlight-Dimm-Demo: wechselt alle 2 s die Helligkeit (erst mit Transistor sichtbar)
const uint8_t LEVELS[] = {255, 128, 40, 0};
const char*   NAMES[]  = {"100%", " 50%", " 15%", " aus"};

void loop() {
  // Zeile 2: Uptime und aktuelle Backlight-Stufe
  uint8_t step = (millis() / 2000) % 4;
  analogWrite(BACKLIGHT, LEVELS[step]);

  lcd.setCursor(0, 1);
  lcd.printf("%4lus  Licht%s", millis() / 1000, NAMES[step]);

  // Lebenszeichen im Serial Monitor, einmal pro Sekunde
  static uint32_t lastLog = 0;
  if (millis() - lastLog >= 1000) {
    lastLog = millis();
    Serial.printf("XIAO lebt! Uptime: %lus\n", millis() / 1000);
  }
  delay(500);
}
#endif
