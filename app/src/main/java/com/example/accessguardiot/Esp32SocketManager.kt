package com.example.accessguardiot

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Protocolo de tramas simples por línea (UTF-8) para enlace Wi-Fi TCP con ESP32 en puerto 5002.
 */
object Esp32Protocol {
    const val PUERTO_ESP32 = 5002
    const val PUERTO_DESCUBRIMIENTO_UDP = 5003
    const val TIPO_SERVICIO_NSD = "_accessguard-esp32._tcp"
    const val PREFIJO_UDP = "ACCESSGUARD_ESP32"
    private const val CLAVE_IOT = "AGIoT_2026_Key32"
    private const val PREFIJO_SEGURO = "SEC|"

    sealed class MensajeEsp32 {
        data object Pong : MensajeEsp32()
        data object Ping : MensajeEsp32()
        data class Hello(val nombre: String = "ESP32") : MensajeEsp32()
        data class Estado(
            val puertaAbierta: Boolean? = null,
            val anguloServo: Int? = null,
            val buzzerActivo: Boolean? = null,
            val modoAcceso: String? = null,
            val detalle: String? = null
        ) : MensajeEsp32()
        data class TarjetaRfid(val uid: String) : MensajeEsp32()
        data class PinBotonera(val pin: String) : MensajeEsp32()
    }

    /** Formatea el estado actual del panel para que el ESP32 actúe sobre relé/servo/buzzer. */
    fun formatearComando(
        puertaAbierta: Boolean,
        anguloServo: Int,
        buzzerActivo: Boolean,
        modoAcceso: String,
        uidRfid: String,
        pinBotonera: String
    ): String {
        val modo = limpiarCampo(modoAcceso.uppercase())
        val uid = limpiarCampo(uidRfid)
        val pin = limpiarCampo(pinBotonera)
        val angulo = anguloServo.coerceIn(0, 180)
        return "CMD|PUERTA=${if (puertaAbierta) 1 else 0}|ANGULO=$angulo|BUZZER=${if (buzzerActivo) 1 else 0}|MODO=$modo|UID=$uid|PIN=$pin"
    }

    /**
     * Cifra una trama hacia/desde el ESP32 con nonce + flujo simétrico + MAC de integridad (FNV-1a 32-bit).
     * Formato: SEC|<nonce4>|<hexCifrado>|<mac8>
     */
    fun cifrarTrama(textoPlano: String, nonceFijo: String? = null): String {
        val limpio = textoPlano.trim()
        val nonce = (nonceFijo ?: generarNonce4()).take(4).padEnd(4, '0').uppercase()
        val bytesPlano = limpio.toByteArray(Charsets.UTF_8)
        val claveBytes = CLAVE_IOT.toByteArray(Charsets.UTF_8)
        val nonceBytes = nonce.toByteArray(Charsets.UTF_8)

        val hexBuilder = StringBuilder(bytesPlano.size * 2)
        for (i in bytesPlano.indices) {
            val k = claveBytes[i % claveBytes.size].toInt() and 0xFF
            val n = nonceBytes[i % nonceBytes.size].toInt() and 0xFF
            val mascara = (k xor n xor ((i * 31) and 0xFF)) and 0xFF
            val cifradoByte = (bytesPlano[i].toInt() and 0xFF) xor mascara
            hexBuilder.append("%02X".format(cifradoByte))
        }
        val hexCifrado = hexBuilder.toString()
        val mac = calcularMacHex(nonce, hexCifrado)
        return "$PREFIJO_SEGURO$nonce|$hexCifrado|$mac"
    }

    /** Verifica el MAC de integridad y descifra una trama SEC|<nonce4>|<hexCifrado>|<mac8>. */
    fun descifrarTrama(tramaSegura: String): String? {
        val limpia = tramaSegura.trim()
        if (!limpia.startsWith(PREFIJO_SEGURO)) return null
        val partes = limpia.split("|")
        if (partes.size != 4) return null

        val nonce = partes[1].trim().uppercase()
        val hexCifrado = partes[2].trim().uppercase()
        val macRecibido = partes[3].trim().uppercase()
        if (nonce.length != 4 || hexCifrado.isEmpty() || hexCifrado.length % 2 != 0) return null

        val macEsperado = calcularMacHex(nonce, hexCifrado)
        if (macRecibido != macEsperado) return null

        val claveBytes = CLAVE_IOT.toByteArray(Charsets.UTF_8)
        val nonceBytes = nonce.toByteArray(Charsets.UTF_8)
        val totalBytes = hexCifrado.length / 2
        val salida = ByteArray(totalBytes)

        for (i in 0 until totalBytes) {
            val byteCifrado = hexCifrado.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            val k = claveBytes[i % claveBytes.size].toInt() and 0xFF
            val n = nonceBytes[i % nonceBytes.size].toInt() and 0xFF
            val mascara = (k xor n xor ((i * 31) and 0xFF)) and 0xFF
            salida[i] = (byteCifrado xor mascara).toByte()
        }
        return String(salida, Charsets.UTF_8)
    }

    private fun calcularMacHex(nonce: String, hexCifrado: String): String {
        val entrada = "$nonce|$hexCifrado|$CLAVE_IOT"
        var hash = 0x811C9DC5L
        for (ch in entrada) {
            hash = hash xor (ch.code.toLong() and 0xFFL)
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return "%08X".format(hash)
    }

    private fun generarNonce4(): String {
        val chars = "0123456789ABCDEF"
        return buildString(4) {
            repeat(4) {
                append(chars[(Math.random() * chars.length).toInt().coerceIn(0, chars.lastIndex)])
            }
        }
    }

    /** Interpreta una línea enviada por el ESP32 hacia la app Android (soporta trama SEC| cifrada o texto). */
    fun parsearMensaje(linea: String): MensajeEsp32? {
        val cruda = linea.trim()
        if (cruda.isEmpty()) return null
        val limpia = if (cruda.startsWith(PREFIJO_SEGURO)) {
            descifrarTrama(cruda) ?: return null
        } else {
            cruda
        }
        val upper = limpia.uppercase()

        return when {
            upper == "PING" -> MensajeEsp32.Ping
            upper == "PONG" || upper == "OK" -> MensajeEsp32.Pong
            upper.startsWith("HELLO") -> {
                val nombre = limpia.substringAfter("|", "ESP32").trim().ifBlank { "ESP32" }
                MensajeEsp32.Hello(nombre)
            }
            upper.startsWith("RFID|") -> {
                val uid = limpiarCampo(limpia.substringAfter("|", "")).replace(" ", "-").uppercase()
                if (uid.isNotBlank()) MensajeEsp32.TarjetaRfid(uid) else null
            }
            upper.startsWith("PIN|") -> {
                val pin = limpiarCampo(limpia.substringAfter("|", ""))
                if (pin.isNotBlank()) MensajeEsp32.PinBotonera(pin) else null
            }
            upper.startsWith("EVT|") || upper.startsWith("ESTADO|") -> parsearEstado(limpia)
            else -> null
        }
    }

    /** Extrae IP y puerto desde un anuncio UDP enviado por el ESP32 ("ACCESSGUARD_ESP32|<ip>|<puerto>"). */
    fun parsearDescubrimientoUdp(texto: String, ipOrigen: String): Pair<String, Int>? {
        val partes = texto.trim().split("|")
        if (partes.firstOrNull()?.uppercase() != PREFIJO_UDP) return null
        val ip = partes.getOrNull(1)?.trim()?.takeIf { esIpv4Valida(it) }
            ?: ipOrigen.trim().takeIf { esIpv4Valida(it) }
            ?: return null
        val puerto = partes.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: PUERTO_ESP32
        return ip to puerto
    }

    private fun parsearEstado(linea: String): MensajeEsp32.Estado? {
        val segmentos = linea.split("|").drop(1)
        var puerta: Boolean? = null
        var angulo: Int? = null
        var buzzer: Boolean? = null
        var modo: String? = null
        var detalle: String? = null

        for (seg in segmentos) {
            val clave = seg.substringBefore("=", "").trim().uppercase()
            val valor = seg.substringAfter("=", "").trim()
            if (clave.isEmpty() || valor.isEmpty()) continue
            when (clave) {
                "PUERTA" -> puerta = parsearBooleano(valor)
                "ANGULO" -> angulo = valor.toIntOrNull()?.coerceIn(0, 180)
                "BUZZER" -> buzzer = parsearBooleano(valor)
                "MODO" -> modo = limpiarCampo(valor.uppercase())
                "DETALLE" -> detalle = limpiarCampo(valor)
            }
        }
        return if (puerta == null && angulo == null && buzzer == null && modo == null && detalle == null) null
        else MensajeEsp32.Estado(puerta, angulo, buzzer, modo, detalle)
    }

    private fun parsearBooleano(valor: String): Boolean? {
        return when (valor.trim().lowercase()) {
            "1", "true", "on", "abierta" -> true
            "0", "false", "off", "cerrada" -> false
            else -> null
        }
    }

    private fun limpiarCampo(valor: String): String {
        return valor.replace("|", "").replace("\n", "").replace("\r", "").trim()
    }

    private fun esIpv4Valida(ip: String): Boolean {
        val octetos = ip.split(".")
        return octetos.size == 4 && octetos.all { it.toIntOrNull() in 0..255 }
    }
}

/**
 * Gestor de enlace Wi-Fi TCP dedicado para hardware ESP32 (puerto 5002).
 * Opera en paralelo al puerto 5000 de sincronización entre dispositivos móviles.
 */
class Esp32SocketManager(context: Context? = null) {

    private val appContext: Context? = context?.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var nsdManager: NsdManager? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var serverJob: Job? = null
    private var udpJob: Job? = null
    private var lectorJob: Job? = null
    private var latidoJob: Job? = null

    @Volatile
    private var socketActivo: Socket? = null

    @Volatile
    private var writerActivo: PrintWriter? = null

    @Volatile
    private var endpointActivo: String? = null

    private var onEstadoEnlaceCb: ((Boolean, String?, String) -> Unit)? = null
    private var onMensajeCb: ((Esp32Protocol.MensajeEsp32) -> Unit)? = null
    private var onTramaCb: ((String) -> Unit)? = null

    fun estaConectado(): Boolean {
        val s = socketActivo ?: return false
        return s.isConnected && !s.isClosed
    }

    /** Inicia servidor TCP en puerto 5002, escucha UDP/mDNS del ESP32 y verificación periódica. */
    fun iniciar(
        onEstadoEnlace: (conectado: Boolean, endpoint: String?, detalle: String) -> Unit,
        onMensajeHardware: (Esp32Protocol.MensajeEsp32) -> Unit,
        onTrama: (String) -> Unit
    ) {
        if (serverJob?.isActive == true) return
        onEstadoEnlaceCb = onEstadoEnlace
        onMensajeCb = onMensajeHardware
        onTramaCb = onTrama

        adquirirMulticastLock()
        iniciarServidorTcp()
        iniciarEscuchaUdp()
        iniciarNsdDiscovery()
        iniciarLoopLatido()
    }

    /** Conecta manualmente al ESP32 cuando este actúa como servidor TCP en la red local. */
    fun conectarManual(ip: String, puerto: Int = Esp32Protocol.PUERTO_ESP32) {
        val ipLimpia = ip.trim()
        if (ipLimpia.isBlank() || puerto !in 1..65535) {
            notificarEstado(false, null, "Ingresa una IP válida para el ESP32")
            return
        }
        scope.launch {
            notificarEstado(estaConectado(), endpointActivo, "Conectando a ESP32 ($ipLimpia:$puerto)...")
            conectarComoCliente(ipLimpia, puerto)
        }
    }

    /** Cierra el enlace TCP activo con el ESP32 (mantiene escuchando el puerto 5002 para nuevas conexiones). */
    fun desconectar() {
        scope.launch {
            cerrarSocketActivo("ESP32 desconectado • Escuchando en puerto ${Esp32Protocol.PUERTO_ESP32}")
        }
    }

    /** Envía el comando de estado actual al ESP32 conectado. */
    fun enviarEstado(
        puertaAbierta: Boolean,
        anguloServo: Int,
        buzzerActivo: Boolean,
        modoAcceso: String,
        uidRfid: String,
        pinBotonera: String
    ) {
        val comando = Esp32Protocol.formatearComando(
            puertaAbierta, anguloServo, buzzerActivo, modoAcceso, uidRfid, pinBotonera
        )
        enviarLinea(comando, resumenVisible = comando)
    }

    fun enviarPing() {
        enviarLinea("PING")
    }

    fun enviarLinea(linea: String, resumenVisible: String = linea) {
        if (!estaConectado()) return
        scope.launch {
            val writer = writerActivo
            if (writer == null) {
                cerrarSocketActivo("Sin enlace ESP32 • Esperando en puerto ${Esp32Protocol.PUERTO_ESP32}")
                return@launch
            }
            writer.println(linea)
            if (writer.checkError()) {
                cerrarSocketActivo("Se perdió la conexión con ESP32")
            } else {
                notificarTrama("TX -> $resumenVisible")
            }
        }
    }

    private fun iniciarServidorTcp() {
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Esp32Protocol.PUERTO_ESP32))
                }
                notificarEstado(false, null, "Escuchando en puerto ${Esp32Protocol.PUERTO_ESP32}")
                while (isActive) {
                    val cliente = serverSocket?.accept() ?: break
                    adoptarSocket(cliente)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun iniciarEscuchaUdp() {
        udpJob = scope.launch {
            try {
                udpSocket = DatagramSocket(Esp32Protocol.PUERTO_DESCUBRIMIENTO_UDP).apply {
                    broadcast = true
                }
                val buffer = ByteArray(512)
                while (isActive) {
                    val paquete = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(paquete) ?: break
                    val texto = String(paquete.data, 0, paquete.length, Charsets.UTF_8)
                    val ipOrigen = paquete.address?.hostAddress.orEmpty()
                    val destino = Esp32Protocol.parsearDescubrimientoUdp(texto, ipOrigen)
                    if (destino != null && !estaConectado()) {
                        conectarComoCliente(destino.first, destino.second)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun iniciarNsdDiscovery() {
        if (android.os.Build.VERSION.SDK_INT >= 36) return
        val ctx = appContext ?: return
        try {
            val manager = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = manager
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!estaConectado()) {
                        resolverServicioNsd(manager, serviceInfo)
                    }
                }
            }
            nsdDiscoveryListener = listener
            manager.discoverServices(Esp32Protocol.TIPO_SERVICIO_NSD, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun resolverServicioNsd(manager: NsdManager, info: NsdServiceInfo) {
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress ?: return
                    val puerto = serviceInfo.port.takeIf { it in 1..65535 } ?: Esp32Protocol.PUERTO_ESP32
                    if (!estaConectado()) {
                        scope.launch { conectarComoCliente(host, puerto) }
                    }
                }
            })
        } catch (_: Exception) {
        }
    }

    private fun iniciarLoopLatido() {
        latidoJob = scope.launch {
            while (isActive) {
                delay(5000L)
                if (estaConectado()) {
                    val writer = writerActivo
                    if (writer != null) {
                        writer.println("PING")
                        if (writer.checkError()) {
                            cerrarSocketActivo("ESP32 sin respuesta • Esperando en puerto ${Esp32Protocol.PUERTO_ESP32}")
                        }
                    }
                }
            }
        }
    }

    private fun conectarComoCliente(ip: String, puerto: Int) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, puerto), 2000)
            adoptarSocket(socket)
        } catch (_: Exception) {
            if (!estaConectado()) {
                notificarEstado(false, null, "No se pudo conectar a ESP32 en $ip:$puerto")
            }
        }
    }

    @Synchronized
    private fun adoptarSocket(nuevoSocket: Socket) {
        cerrarSocketSilencioso()
        nuevoSocket.keepAlive = true
        nuevoSocket.tcpNoDelay = true
        socketActivo = nuevoSocket
        writerActivo = PrintWriter(OutputStreamWriter(nuevoSocket.getOutputStream(), Charsets.UTF_8), true)
        val ipRemota = nuevoSocket.inetAddress?.hostAddress ?: "ESP32"
        val puertoRemoto = nuevoSocket.port
        val endpoint = "$ipRemota:$puertoRemoto"
        endpointActivo = endpoint

        writerActivo?.println("HELLO|ACCESSGUARD_APP")
        notificarEstado(true, endpoint, "Conectado al ESP32 ($endpoint)")

        lectorJob?.cancel()
        lectorJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(nuevoSocket.getInputStream(), Charsets.UTF_8))
                while (isActive) {
                    val linea = reader.readLine() ?: break
                    val textoDescifrado = if (linea.trim().startsWith("SEC|")) {
                        Esp32Protocol.descifrarTrama(linea) ?: "[Trama SEC rechazada por integridad]"
                    } else {
                        linea
                    }
                    notificarTrama("RX <- $textoDescifrado")
                    val mensaje = Esp32Protocol.parsearMensaje(linea)
                    when (mensaje) {
                        is Esp32Protocol.MensajeEsp32.Ping -> {
                            writerActivo?.println("PONG")
                        }
                        null, is Esp32Protocol.MensajeEsp32.Pong, is Esp32Protocol.MensajeEsp32.Hello -> Unit
                        else -> withContext(Dispatchers.Main) {
                            onMensajeCb?.invoke(mensaje)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (socketActivo === nuevoSocket) {
                    cerrarSocketActivo("ESP32 desconectado • Escuchando en puerto ${Esp32Protocol.PUERTO_ESP32}")
                }
            }
        }
    }

    private fun cerrarSocketActivo(detalle: String) {
        cerrarSocketSilencioso()
        notificarEstado(false, null, detalle)
    }

    private fun cerrarSocketSilencioso() {
        endpointActivo = null
        try { writerActivo?.close() } catch (_: Exception) {}
        try { socketActivo?.close() } catch (_: Exception) {}
        writerActivo = null
        socketActivo = null
    }

    private fun notificarEstado(conectado: Boolean, endpoint: String?, detalle: String) {
        scope.launch(Dispatchers.Main) {
            onEstadoEnlaceCb?.invoke(conectado, endpoint, detalle)
        }
    }

    private fun notificarTrama(trama: String) {
        val segura = trama
            .replace(Regex("UID=[^|]+"), "UID=****")
            .replace(Regex("PIN=[^|]+"), "PIN=****")
        scope.launch(Dispatchers.Main) {
            onTramaCb?.invoke(segura)
        }
    }

    private fun adquirirMulticastLock() {
        val ctx = appContext ?: return
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wm.createMulticastLock("AccessGuardEsp32Lock").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    fun detener() {
        latidoJob?.cancel()
        lectorJob?.cancel()
        udpJob?.cancel()
        serverJob?.cancel()
        try { nsdDiscoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        cerrarSocketSilencioso()
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) {}
    }
}
