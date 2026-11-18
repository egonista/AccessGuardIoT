/**
 * ============================================================================
 * PROYECTO: PROTOTIPO DE CONTROL DE ACCESO DUAL PARA ÁREAS RESTRINGIDAS
 * ASIGNATURA: Aplicaciones Móviles para IoT (TI3042) - Unidad 3
 * INSTITUCIÓN: INACAP
 * AUTORES: Egon Muñoz
 * PLACA: ESP32 DevKit V1 (Wi-Fi TCP Puerto 5002 + RFID + Teclado 4x4)
 * ============================================================================
 */

#include <WiFi.h>
#include <WiFiUdp.h>
#include <SPI.h>
#include <MFRC522.h>
#include <ESP32Servo.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <Keypad.h>

// ============================================================================
// CONFIGURACIÓN WI-FI, SERVIDOR TCP (5002) Y CLAVE DE INTEGRIDAD / CIFRADO
// ============================================================================
// Configurar credenciales locales antes de compilar
const char* WIFI_SSID = "TU_RED_WIFI";
const char* WIFI_PASS = "TU_CONTRASENA_WIFI";
const int PUERTO_TCP  = 5002;
const int PUERTO_UDP  = 5003;
const char* CLAVE_IOT = "TU_CLAVE_IOT_COMPARTIDA";

WiFiServer servidorTCP(PUERTO_TCP);
WiFiClient clienteApp;
WiFiUDP udp;

// ============================================================================
// PINOUT ESP32 (Coincide con tu circuito en Wokwi)
// ============================================================================
#define RFID_SCK   18
#define RFID_MISO  19
#define RFID_MOSI  23
#define SS_PIN     5
#define RST_PIN    27

#define LCD_SDA    21
#define LCD_SCL    22

#define SERVO_PIN  13
#define BUZZER_PIN 4
#define LED_VERDE  2
#define LED_ROJO   15

// Teclado Matricial 4x4
const byte FILAS = 4;
const byte COLUMNAS = 4;
char teclas[FILAS][COLUMNAS] = {
  {'1', '2', '3', 'A'},
  {'4', '5', '6', 'B'},
  {'7', '8', '9', 'C'},
  {'*', '0', '#', 'D'}
};
byte pinesFilas[FILAS]       = {32, 33, 25, 26}; // R1, R2, R3, R4
byte pinesColumnas[COLUMNAS] = {14, 12, 16, 17}; // C1, C2, C3, C4

// ============================================================================
// OBJETOS Y ESTADO SINCRONIZADO CON LA APP
// ============================================================================
MFRC522 rfid(SS_PIN, RST_PIN);
Servo cerrojo;
LiquidCrystal_I2C lcd(0x27, 16, 2);
Keypad teclado = Keypad(makeKeymap(teclas), pinesFilas, pinesColumnas, FILAS, COLUMNAS);

String uidAutorizado     = "A4-9B-3C-12";
const String UID_WOKWI   = "01-02-03-04"; // Tarjeta virtual por defecto en Wokwi
String pinAutorizado     = "4829";        // PIN inicial sincronizado con la app
String modoAcceso        = "RFID";        // "RFID", "BOTONERA", "RFID_PIN" o "LOCKDOWN"

bool puertaAbierta       = false;
int anguloActual         = 0;
bool buzzerActivo        = false;
bool rfidPaso1Verificado = false;

String pinIngresado = "";
bool rfidEnError = false;
bool cierreAutoPendiente = false;
unsigned long tiempoAperturaFisica = 0;
unsigned long ultimoReintentoRFID = 0;
unsigned long ultimoAnuncioUDP = 0;
unsigned long ultimoBeepAlarma = 0;

// Optimización de energía (apagado automático de luz LCD tras inactividad)
bool lcdEncendido = true;
unsigned long ultimaActividadMs = 0;
const unsigned long TIEMPO_AHORRO_LCD_MS = 20000;

// Buffer circular para almacenamiento temporal de eventos cuando no hay conexión con la App
const int MAX_COLA_OFFLINE = 15;
String colaEventosOffline[MAX_COLA_OFFLINE];
int indiceInicioCola = 0;
int totalPendientesOffline = 0;

// Declaraciones previas
void mostrarEstadoEspera();
void enviarLineaApp(const String& linea);
void enviarEventoSeguroApp(const String& eventoPlano);

// ============================================================================
// BUZZER Y GESTIÓN DE ENERGÍA DEL LCD
// ============================================================================
void despertarLCD() {
  ultimaActividadMs = millis();
  if (!lcdEncendido) {
    lcd.backlight();
    lcdEncendido = true;
  }
}

void sonarBuzzer(int frecuenciaHz, int duracionMs) {
  long periodoMicros = 1000000L / frecuenciaHz;
  long mitadPeriodo = periodoMicros / 2;
  long ciclos = ((long)duracionMs * 1000L) / periodoMicros;

  for (long i = 0; i < ciclos; i++) {
    digitalWrite(BUZZER_PIN, HIGH);
    delayMicroseconds(mitadPeriodo);
    digitalWrite(BUZZER_PIN, LOW);
    delayMicroseconds(mitadPeriodo);
  }
}

// ============================================================================
// CIFRADO SIMÉTRICO + MAC DE INTEGRIDAD FNV-1a (ISO/IEC 27000)
// Formato en tránsito: SEC|<nonce4>|<hexCifrado>|<mac8>
// ============================================================================
String byteAHex(uint8_t b) {
  const char* hex = "0123456789ABCDEF";
  String s = "";
  s += hex[(b >> 4) & 0x0F];
  s += hex[b & 0x0F];
  return s;
}

int hexAByte(char hi, char lo) {
  auto val = [](char c) -> int {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
  };
  int h = val(hi);
  int l = val(lo);
  if (h < 0 || l < 0) return -1;
  return (h << 4) | l;
}

String calcularMacHex(const String& nonce, const String& hexCifrado) {
  String entrada = nonce + "|" + hexCifrado + "|" + String(CLAVE_IOT);
  uint32_t hash = 0x811C9DC5UL;
  for (unsigned int i = 0; i < entrada.length(); i++) {
    hash ^= (uint8_t)entrada[i];
    hash *= 0x01000193UL;
  }
  char buf[9];
  snprintf(buf, sizeof(buf), "%08lX", (unsigned long)hash);
  return String(buf);
}

String cifrarTramaSec(const String& textoPlano) {
  const char* hexChars = "0123456789ABCDEF";
  String nonce = "";
  for (int i = 0; i < 4; i++) {
    nonce += hexChars[random(0, 16)];
  }

  int claveLen = strlen(CLAVE_IOT);
  String hexCifrado = "";
  for (unsigned int i = 0; i < textoPlano.length(); i++) {
    uint8_t k = (uint8_t)CLAVE_IOT[i % claveLen];
    uint8_t n = (uint8_t)nonce[i % 4];
    uint8_t mascara = (uint8_t)((k ^ n ^ ((i * 31) & 0xFF)) & 0xFF);
    uint8_t cifrado = ((uint8_t)textoPlano[i]) ^ mascara;
    hexCifrado += byteAHex(cifrado);
  }

  String mac = calcularMacHex(nonce, hexCifrado);
  return "SEC|" + nonce + "|" + hexCifrado + "|" + mac;
}

String descifrarTramaSec(const String& trama) {
  if (!trama.startsWith("SEC|")) return "";
  int p1 = trama.indexOf('|');
  int p2 = trama.indexOf('|', p1 + 1);
  int p3 = trama.indexOf('|', p2 + 1);
  if (p1 < 0 || p2 < 0 || p3 < 0) return "";

  String nonce = trama.substring(p1 + 1, p2);
  String hexCifrado = trama.substring(p2 + 1, p3);
  String macRecibido = trama.substring(p3 + 1);
  nonce.trim();
  hexCifrado.trim();
  macRecibido.trim();
  nonce.toUpperCase();
  hexCifrado.toUpperCase();
  macRecibido.toUpperCase();

  if (nonce.length() != 4 || hexCifrado.length() == 0 || (hexCifrado.length() % 2) != 0) {
    return "";
  }
  if (calcularMacHex(nonce, hexCifrado) != macRecibido) {
    Serial.println(F("[SEGURIDAD] Trama SEC descartada: fallo de integridad MAC"));
    return "";
  }

  int claveLen = strlen(CLAVE_IOT);
  int totalBytes = hexCifrado.length() / 2;
  String salida = "";
  for (int i = 0; i < totalBytes; i++) {
    int b = hexAByte(hexCifrado[i * 2], hexCifrado[i * 2 + 1]);
    if (b < 0) return "";
    uint8_t k = (uint8_t)CLAVE_IOT[i % claveLen];
    uint8_t n = (uint8_t)nonce[i % 4];
    uint8_t mascara = (uint8_t)((k ^ n ^ ((i * 31) & 0xFF)) & 0xFF);
    salida += (char)(((uint8_t)b) ^ mascara);
  }
  return salida;
}

// ============================================================================
// COLA DE ALMACENAMIENTO TEMPORAL OFFLINE Y ENVÍO A LA APP
// ============================================================================
void guardarEventoOffline(const String& eventoPlano) {
  int pos = (indiceInicioCola + totalPendientesOffline) % MAX_COLA_OFFLINE;
  if (totalPendientesOffline == MAX_COLA_OFFLINE) {
    indiceInicioCola = (indiceInicioCola + 1) % MAX_COLA_OFFLINE;
  } else {
    totalPendientesOffline++;
  }
  colaEventosOffline[pos] = eventoPlano + " [Sincronizado desde memoria ESP32]";
  Serial.println("[OFFLINE] Evento guardado en buffer ESP32 (pendientes: " + String(totalPendientesOffline) + ")");
}

void vaciarColaOfflineHaciaApp() {
  if (!clienteApp || !clienteApp.connected() || totalPendientesOffline == 0) return;
  Serial.println("[SYNC] Enviando " + String(totalPendientesOffline) + " eventos almacenados offline...");
  while (totalPendientesOffline > 0 && clienteApp.connected()) {
    String ev = colaEventosOffline[indiceInicioCola];
    indiceInicioCola = (indiceInicioCola + 1) % MAX_COLA_OFFLINE;
    totalPendientesOffline--;
    clienteApp.println(cifrarTramaSec(ev));
    delay(25);
  }
}

String normalizarUID(String u) {
  u.trim();
  u.replace(" ", "-");
  u.toUpperCase();
  return u;
}

String enmascararUID(const String& u) {
  String limpio = normalizarUID(u);
  if (limpio.length() <= 2) return "****";
  return "****-" + limpio.substring(limpio.length() - 2);
}

bool esUidValido(const String& uidLeido) {
  String limpio = normalizarUID(uidLeido);
  return (limpio == normalizarUID(uidAutorizado)) || (limpio == UID_WOKWI);
}

void enviarLineaApp(const String& linea) {
  if (clienteApp && clienteApp.connected()) {
    clienteApp.println(linea);
  }
}

void enviarEventoSeguroApp(const String& eventoPlano) {
  if (clienteApp && clienteApp.connected()) {
    clienteApp.println(cifrarTramaSec(eventoPlano));
    Serial.println("[TX Seguro -> App] " + eventoPlano);
  } else {
    guardarEventoOffline(eventoPlano);
  }
}

void anunciarPorUDP() {
  if (WiFi.status() != WL_CONNECTED) return;
  if (clienteApp && clienteApp.connected()) return;
  if (millis() - ultimoAnuncioUDP < 4000) return;

  ultimoAnuncioUDP = millis();
  String anuncio = "ACCESSGUARD_ESP32|" + WiFi.localIP().toString() + "|" + String(PUERTO_TCP);
  udp.beginPacket(IPAddress(255, 255, 255, 255), PUERTO_UDP);
  udp.print(anuncio);
  udp.endPacket();
}

// ============================================================================
// CONEXIÓN WI-FI (CON AHORRO DE ENERGÍA MODEM-SLEEP) Y WATCHDOG RFID
// ============================================================================
void conectarWiFi() {
  despertarLCD();
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("Conectando WiFi");
  Serial.print("Conectando a Wi-Fi: ");
  Serial.println(WIFI_SSID);

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(true); // Optimización energética Modem-sleep en ESP32
  WiFi.begin(WIFI_SSID, WIFI_PASS);

  int intentos = 0;
  while (WiFi.status() != WL_CONNECTED && intentos < 20) {
    delay(500);
    Serial.print(".");
    lcd.setCursor(intentos % 16, 1);
    lcd.print(".");
    intentos++;
  }

  lcd.clear();
  if (WiFi.status() == WL_CONNECTED) {
    servidorTCP.begin();
    servidorTCP.setNoDelay(true);
    udp.begin(PUERTO_UDP);

    Serial.println(F("\n[OK] Wi-Fi conectado!"));
    Serial.print(F("IP del ESP32: "));
    Serial.println(WiFi.localIP());
    Serial.print(F("Servidor TCP escuchando en puerto: "));
    Serial.println(PUERTO_TCP);

    lcd.setCursor(0, 0);
    lcd.print("WiFi OK :5002");
    lcd.setCursor(0, 1);
    lcd.print(WiFi.localIP());
    delay(2000);
  } else {
    Serial.println(F("\n[AVISO] Sin Wi-Fi inicial. Modo autonomo + buffer offline activo."));
    lcd.setCursor(0, 0);
    lcd.print("Sin WiFi (Local)");
    delay(1500);
  }
}

void reiniciarHardwareRFID() {
  pinMode(RST_PIN, OUTPUT);
  digitalWrite(RST_PIN, LOW);
  delay(50);
  digitalWrite(RST_PIN, HIGH);
  delay(50);

  SPI.end();
  delay(20);
  SPI.begin(RFID_SCK, RFID_MISO, RFID_MOSI, SS_PIN);
  rfid.PCD_Init();
}

bool verificarSaludRFID() {
  byte version = rfid.PCD_ReadRegister(rfid.VersionReg);

  if (version == 0x00 || version == 0xFF) {
    if (!rfidEnError) {
      rfidEnError = true;
      despertarLCD();
      Serial.println(F("\n[ALERTA] RFID desconectado o sin energia (revise 3.3V/cables)."));
      lcd.clear();
      lcd.setCursor(0, 0);
      lcd.print("ERROR EN RFID!");
      lcd.setCursor(0, 1);
      lcd.print("Revise cables...");
      digitalWrite(LED_ROJO, HIGH);
      digitalWrite(LED_VERDE, LOW);
    }

    if (millis() - ultimoReintentoRFID >= 1500) {
      ultimoReintentoRFID = millis();
      reiniciarHardwareRFID();

      byte nuevaVersion = rfid.PCD_ReadRegister(rfid.VersionReg);
      if (nuevaVersion != 0x00 && nuevaVersion != 0xFF) {
        rfidEnError = false;
        despertarLCD();
        Serial.print(F("[OK] RFID recuperado. Firmware: 0x"));
        Serial.println(nuevaVersion, HEX);
        lcd.clear();
        lcd.setCursor(0, 0);
        lcd.print("RFID CONECTADO!");
        delay(1000);
        mostrarEstadoEspera();
      }
    }
    return false;
  }

  if (rfidEnError) {
    rfidEnError = false;
    mostrarEstadoEspera();
  }
  return true;
}

// ============================================================================
// ESTADOS Y ACCIONES DEL CERROJO
// ============================================================================
void aplicarHardwareFisico() {
  cerrojo.write(anguloActual);
  if (puertaAbierta && modoAcceso != "LOCKDOWN") {
    digitalWrite(LED_VERDE, HIGH);
    digitalWrite(LED_ROJO, LOW);
  } else {
    digitalWrite(LED_VERDE, LOW);
    digitalWrite(LED_ROJO, HIGH);
  }
}

void mostrarEstadoEspera() {
  despertarLCD();
  pinIngresado = "";
  aplicarHardwareFisico();

  lcd.clear();
  if (modoAcceso == "LOCKDOWN") {
    lcd.setCursor(0, 0);
    lcd.print("BLOQUEO LOCKDOWN");
    lcd.setCursor(0, 1);
    lcd.print("Solo Supervisor");
    return;
  }

  if (puertaAbierta) {
    lcd.setCursor(0, 0);
    lcd.print("PUERTA ABIERTA");
    lcd.setCursor(0, 1);
    lcd.print("Angulo: " + String(anguloActual) + " grad");
    return;
  }

  lcd.setCursor(0, 0);
  if (modoAcceso == "RFID") {
    lcd.print("MODO: SOLO RFID");
    lcd.setCursor(0, 1);
    lcd.print("Acerque Tarjeta");
  } else if (modoAcceso == "BOTONERA") {
    lcd.print("MODO: SOLO PIN");
    lcd.setCursor(0, 1);
    lcd.print("Ingrese PIN + #");
  } else if (modoAcceso == "RFID_PIN") {
    if (rfidPaso1Verificado) {
      lcd.print("1/2 RFID OK!");
      lcd.setCursor(0, 1);
      lcd.print("Ingrese PIN + #");
    } else {
      lcd.print("MODO: RFID + PIN");
      lcd.setCursor(0, 1);
      lcd.print("1) Acerque RFID");
    }
  } else {
    lcd.print("SISTEMA LISTO");
    lcd.setCursor(0, 1);
    lcd.print("Tarjeta o PIN:#");
  }
}

void concederAccesoFisico(String detalleCorto, String detalleBitacora) {
  despertarLCD();
  Serial.println("\nACCESO AUTORIZADO (" + detalleCorto + ")");
  rfidPaso1Verificado = false;
  puertaAbierta = true;
  anguloActual = 90;
  buzzerActivo = false;
  cierreAutoPendiente = true;
  tiempoAperturaFisica = millis();

  aplicarHardwareFisico();

  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("ACCESO CONCEDIDO");
  lcd.setCursor(0, 1);
  lcd.print(detalleCorto.substring(0, 16));

  sonarBuzzer(2000, 100);
  delay(50);
  sonarBuzzer(2000, 100);

  enviarEventoSeguroApp(
    "EVT|PUERTA=1|ANGULO=90|BUZZER=0|MODO=" + modoAcceso + "|DETALLE=" + detalleBitacora
  );
}

void denegarAccesoFisico(String motivoCorto, String detalleBitacora) {
  despertarLCD();
  Serial.println("\nALERTA: ACCESO DENEGADO - " + motivoCorto);
  rfidPaso1Verificado = false;
  puertaAbierta = false;
  anguloActual = 0;
  buzzerActivo = true;
  cierreAutoPendiente = false;

  aplicarHardwareFisico();

  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("ACCESO DENEGADO!");
  lcd.setCursor(0, 1);
  lcd.print(motivoCorto.substring(0, 16));

  enviarEventoSeguroApp(
    "EVT|PUERTA=0|ANGULO=0|BUZZER=1|MODO=" + modoAcceso + "|DETALLE=" + detalleBitacora
  );

  for (int i = 0; i < 3; i++) {
    digitalWrite(LED_ROJO, LOW);
    sonarBuzzer(500, 150);
    digitalWrite(LED_ROJO, HIGH);
    delay(100);
  }

  delay(800);
  mostrarEstadoEspera();
}

String obtenerUID() {
  String uid = "";
  for (byte i = 0; i < rfid.uid.size; i++) {
    if (i > 0) uid += "-";
    if (rfid.uid.uidByte[i] < 0x10) uid += "0";
    uid += String(rfid.uid.uidByte[i], HEX);
  }
  uid.toUpperCase();
  return uid;
}

// ============================================================================
// PROCESAMIENTO DE COMANDOS DESDE LA APP ANDROID (PUERTO 5002)
// Soporta tramas cifradas SEC|<nonce>|<hex>|<mac> y comandos de diagnóstico
// ============================================================================
String extraerValorCampo(const String& trama, const String& clave) {
  String prefijo = clave + "=";
  int inicio = trama.indexOf(prefijo);
  if (inicio == -1) return "";
  inicio += prefijo.length();
  int fin = trama.indexOf('|', inicio);
  if (fin == -1) return trama.substring(inicio);
  return trama.substring(inicio, fin);
}

void procesarComandoApp(String comandoCrudo) {
  comandoCrudo.trim();
  if (comandoCrudo.length() == 0) return;

  String comando = comandoCrudo;
  if (comandoCrudo.startsWith("SEC|")) {
    comando = descifrarTramaSec(comandoCrudo);
    if (comando.length() == 0) return;
  }

  String upper = comando;
  upper.toUpperCase();
  if (upper.startsWith("CMD|")) {
    Serial.println(F("\n[RX <- App:5002] Trama CMD verificada y descifrada"));
  } else {
    Serial.println("\n[RX <- App:5002] " + comando);
  }

  if (upper == "PING") {
    enviarLineaApp("PONG");
    return;
  }
  if (upper.startsWith("HELLO")) {
    enviarLineaApp("HELLO|ESP32_ACCESSGUARD");
    vaciarColaOfflineHaciaApp();
    return;
  }
  if (upper == "ABRIR" || upper == "OPEN") {
    concederAccesoFisico("App Remota", "abrio la puerta desde comando directo");
    return;
  }
  if (upper == "ESTADO" || upper == "STATUS") {
    enviarEventoSeguroApp(
      "ESTADO|PUERTA=" + String(puertaAbierta ? 1 : 0) +
      "|ANGULO=" + String(anguloActual) +
      "|BUZZER=" + String(buzzerActivo ? 1 : 0) +
      "|MODO=" + modoAcceso
    );
    return;
  }

  if (upper.startsWith("CMD|")) {
    despertarLCD();
    String valPuerta = extraerValorCampo(comando, "PUERTA");
    String valAngulo = extraerValorCampo(comando, "ANGULO");
    String valBuzzer = extraerValorCampo(comando, "BUZZER");
    String valModo   = extraerValorCampo(comando, "MODO");
    String valUid    = extraerValorCampo(comando, "UID");
    String valPin    = extraerValorCampo(comando, "PIN");

    bool puertaAnterior = puertaAbierta;
    if (valModo.length() > 0) {
      valModo.toUpperCase();
      if (valModo != modoAcceso) {
        rfidPaso1Verificado = false;
      }
      modoAcceso = valModo;
    }
    if (valUid.length() >= 4) {
      uidAutorizado = normalizarUID(valUid);
    }
    if (valPin.length() >= 4) {
      pinAutorizado = valPin;
    }
    if (valPuerta.length() > 0) {
      puertaAbierta = (valPuerta == "1" || valPuerta.equalsIgnoreCase("true"));
    }
    if (valAngulo.length() > 0) {
      anguloActual = constrain(valAngulo.toInt(), 0, 180);
    } else {
      anguloActual = puertaAbierta ? 90 : 0;
    }
    if (valBuzzer.length() > 0) {
      buzzerActivo = (valBuzzer == "1" || valBuzzer.equalsIgnoreCase("true"));
    }

    if (modoAcceso == "LOCKDOWN") {
      puertaAbierta = false;
      anguloActual = 0;
      cierreAutoPendiente = false;
    } else if (puertaAbierta != puertaAnterior) {
      cierreAutoPendiente = false;
      sonarBuzzer(1800, 80);
    }

    aplicarHardwareFisico();
    mostrarEstadoEspera();
    enviarLineaApp("PONG");
    vaciarColaOfflineHaciaApp();
  }
}

void atenderClienteWiFi() {
  if (WiFi.status() != WL_CONNECTED) return;

  anunciarPorUDP();

  if (servidorTCP.hasClient()) {
    WiFiClient nuevoCliente = servidorTCP.available();
    if (nuevoCliente) {
      if (clienteApp && clienteApp.connected()) {
        clienteApp.stop();
      }
      clienteApp = nuevoCliente;
      clienteApp.setNoDelay(true);
      Serial.print(F("\n[OK] App Android conectada desde: "));
      Serial.println(clienteApp.remoteIP());
      enviarLineaApp("HELLO|ESP32_ACCESSGUARD");
      vaciarColaOfflineHaciaApp();
    }
  }

  while (clienteApp && clienteApp.connected() && clienteApp.available()) {
    String linea = clienteApp.readStringUntil('\n');
    procesarComandoApp(linea);
  }
}

// ============================================================================
// CONFIGURACIÓN INICIAL
// ============================================================================
void setup() {
  Serial.begin(115200);
  randomSeed(micros());

  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(LED_VERDE, OUTPUT);
  pinMode(LED_ROJO, OUTPUT);

  ESP32PWM::allocateTimer(0);
  ESP32PWM::allocateTimer(1);
  ESP32PWM::allocateTimer(2);
  ESP32PWM::allocateTimer(3);
  cerrojo.setPeriodHertz(50);
  cerrojo.attach(SERVO_PIN, 500, 2400);
  cerrojo.write(0);

  Wire.begin(LCD_SDA, LCD_SCL);
  lcd.init();
  lcd.backlight();
  ultimaActividadMs = millis();

  conectarWiFi();

  SPI.begin(RFID_SCK, RFID_MISO, RFID_MOSI, SS_PIN);
  rfid.PCD_Init();

  mostrarEstadoEspera();
}

// ============================================================================
// BUCLE PRINCIPAL
// ============================================================================
void loop() {
  atenderClienteWiFi();

  // Ahorro energético: apagar luz del LCD tras 20 s sin interacción
  if (lcdEncendido && !puertaAbierta && !buzzerActivo &&
      (millis() - ultimaActividadMs >= TIEMPO_AHORRO_LCD_MS)) {
    lcd.noBacklight();
    lcdEncendido = false;
  }

  // Cierre automático tras 4 segundos cuando se abrió con tarjeta o PIN físico
  if (cierreAutoPendiente && (millis() - tiempoAperturaFisica >= 4000)) {
    cierreAutoPendiente = false;
    puertaAbierta = false;
    anguloActual = 0;
    mostrarEstadoEspera();
    enviarEventoSeguroApp(
      "EVT|PUERTA=0|ANGULO=0|BUZZER=" + String(buzzerActivo ? 1 : 0) +
      "|MODO=" + modoAcceso + "|DETALLE=cerro la puerta automaticamente en ESP32"
    );
  }

  if (buzzerActivo && (millis() - ultimoBeepAlarma >= 900)) {
    ultimoBeepAlarma = millis();
    sonarBuzzer(700, 120);
  }

  // 1. Lectura del Teclado Matricial 4x4
  char tecla = teclado.getKey();
  if (tecla) {
    despertarLCD();
    sonarBuzzer(1200, 40);

    if (modoAcceso == "LOCKDOWN") {
      denegarAccesoFisico("MODO LOCKDOWN", "Intento de PIN rechazado en ESP32: modo LOCKDOWN activo");
      return;
    }
    if (modoAcceso == "RFID") {
      lcd.clear();
      lcd.setCursor(0, 0);
      lcd.print("MODO SOLO RFID!");
      lcd.setCursor(0, 1);
      lcd.print("Use Tarjeta RFID");
      delay(1000);
      mostrarEstadoEspera();
      return;
    }

    if (tecla == '*') {
      pinIngresado = "";
      mostrarEstadoEspera();
    } else if (tecla == '#') {
      if (modoAcceso == "RFID_PIN" && !rfidPaso1Verificado) {
        lcd.clear();
        lcd.setCursor(0, 0);
        lcd.print("FALTA PASO 1!");
        lcd.setCursor(0, 1);
        lcd.print("Acerque RFID 1ro");
        sonarBuzzer(500, 200);
        delay(1200);
        mostrarEstadoEspera();
      } else if (pinIngresado == pinAutorizado) {
        String det = (modoAcceso == "RFID_PIN")
          ? "abrio puerta con Doble Factor (RFID + PIN) en ESP32"
          : "abrio puerta con PIN autorizado en botonera ESP32";
        concederAccesoFisico("PIN Correcto", det);
      } else {
        denegarAccesoFisico("PIN Incorrecto", "PIN incorrecto ingresado en botonera ESP32");
      }
      pinIngresado = "";
    } else if (pinIngresado.length() < 8) {
      pinIngresado += tecla;
      lcd.setCursor(0, 1);
      lcd.print("PIN:            ");
      lcd.setCursor(5, 1);
      for (unsigned int i = 0; i < pinIngresado.length(); i++) {
        lcd.print("*");
      }
    }
  }

  // 2. Salud del lector RFID y lectura de tarjetas
  if (!verificarSaludRFID()) {
    return;
  }

  if (rfid.PICC_IsNewCardPresent() && rfid.PICC_ReadCardSerial()) {
    despertarLCD();
    String uidLeido = obtenerUID();
    String uidSeguro = enmascararUID(uidLeido);
    Serial.println("\nTarjeta detectada. UID enmascarado: " + uidSeguro);

    if (modoAcceso == "LOCKDOWN") {
      denegarAccesoFisico("MODO LOCKDOWN", "Tarjeta RFID rechazada en ESP32: modo LOCKDOWN activo");
    } else if (modoAcceso == "BOTONERA") {
      lcd.clear();
      lcd.setCursor(0, 0);
      lcd.print("MODO SOLO PIN!");
      lcd.setCursor(0, 1);
      lcd.print("Use Teclado 4x4");
      sonarBuzzer(600, 150);
      delay(1000);
      mostrarEstadoEspera();
    } else if (esUidValido(uidLeido)) {
      if (modoAcceso == "RFID_PIN") {
        rfidPaso1Verificado = true;
        sonarBuzzer(1800, 100);
        mostrarEstadoEspera();
      } else {
        concederAccesoFisico("Tarjeta Validada", "abrio puerta con tarjeta RFID autorizada (" + uidSeguro + ")");
      }
    } else {
      denegarAccesoFisico("RFID Invalido", "Tarjeta RFID no autorizada detectada en ESP32 (" + uidSeguro + ")");
    }

    rfid.PICC_HaltA();
    rfid.PCD_StopCrypto1();
  }
}
