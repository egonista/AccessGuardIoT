package com.example.accessguardiot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private data class InterfazLanLocal(
    val nombre: String,
    val direccion: Inet4Address,
    val ip: String,
    val broadcast: String?,
    val prioridad: Int
)

// Estructura completa que viaja cifrada en AES-128 (Estado + Historial SQLite + Operadores)
data class EstadoSincronizadoIoT(
    val puertaAbierta: Boolean,
    val anguloServo: Int,
    val buzzerActivo: Boolean,
    val modoAcceso: String, // "RFID", "BOTONERA", "RFID_PIN" o "LOCKDOWN"
    val uidRfid: String,
    val pinBotonera: String,
    val rolEmisor: String,
    val detalleEvento: String,
    val timestampModificacion: Long = System.currentTimeMillis(),
    val esSincronizacionInicial: Boolean = false,
    val historialEventos: List<EventoBitacora> = emptyList(),
    val operadoresRegistrados: List<UsuarioOperador> = emptyList(),
    val timestampOperadores: Long = 0L,
    val claveSupervisor: String = "",
    val tramaCifradaAES: String = "",
    val codigoAuthHash: String = "",
    val codigoAuthCifrado: String = "",
    val timestampCodigoAuth: Long = 0L
)

/**
 * Gestor de Socket TCP/IP Bidireccional Persistente sobre Wi-Fi / Hotspot (Puerto 5000)
 * + Autodescubrimiento automático (NsdManager, UDP Broadcast por interfaz, Gateway del sistema y escaneo de subred).
 */
class WifiSocketManager(context: Context? = null) {

    companion object {
        const val PUERTO_IOT = 5000
        const val PUERTO_DESCUBRIMIENTO_UDP = 5001
        const val TIPO_SERVICIO_NSD = "_accessguard._tcp"
        private const val FIRMA_UDP = "ACCESSGUARD_IOT"
        private const val TOKEN_DESCUBRIMIENTO = "DESCUBRIMIENTO_AGIOT"

        // IPs típicas: Zona Wi-Fi Android (192.168.43.1), Hotspot de Windows (192.168.137.1) y PC anfitrión desde el Emulador (10.0.2.2)
        private val IPS_HOTSPOT_EMULADOR = listOf("192.168.43.1", "192.168.137.1", "10.0.2.2")

        // Desempate: la IP menor conecta de inmediato; la mayor espera este tiempo antes de intentar (evita conexiones cruzadas)
        private const val ESPERA_DESEMPATE_MS = 2500L

        // Tiempos de sondeo rápido en red local Wi-Fi / Hotspot
        private const val TIMEOUT_CONEXION_AUTO_MS = 500
        private const val TIMEOUT_ESCANEO_MS = 350

        // Si no llega ninguna trama ni latido en este tiempo, se considera el enlace caído y se reconecta
        private const val TIMEOUT_SIN_DATOS_MS = 16000L

        // Tiempo máximo para que el otro extremo responda una trama AES válida (si no, no es AccessGuard IoT)
        private const val TIMEOUT_VERIFICACION_MS = 6000L
    }

    private val appContext: Context? = context?.applicationContext

    private var serverSocket: ServerSocket? = null
    private var servidorJob: Job? = null
    private var lectorClienteJob: Job? = null

    // Socket bidireccional activo (compartido entre Emulador y Teléfono tras conectar)
    @Volatile
    private var socketActivo: Socket? = null

    @Volatile
    private var writerActivo: PrintWriter? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    // Callbacks registrados desde la UI
    private var callbackMensajeRecibido: ((EstadoSincronizadoIoT, String) -> Unit)? = null
    private var callbackEstadoConexion: ((String) -> Unit)? = null
    private var proveedorEstadoActual: (() -> EstadoSincronizadoIoT)? = null

    // ---------------------------------------------------------------------------------
    // Estado del enlace verificado (verde/amarillo en la UI) y autodescubrimiento
    // ---------------------------------------------------------------------------------
    @Volatile
    private var enlaceVerificado = false

    @Volatile
    private var ipEnlaceActual: String? = null

    @Volatile
    private var ultimaRecepcionMs = 0L

    @Volatile
    private var inicioConexionActualMs = 0L

    @Volatile
    private var ultimaIpExitosa: String? = null

    @Volatile
    private var desconectadoDesdeMs = System.currentTimeMillis()

    private val ipsDescubiertas: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val ipsNoValidas: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lockConexion = Any()

    private var autoConexionJob: Job? = null
    private var udpEscuchaJob: Job? = null

    @Volatile
    private var socketUdpEscucha: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var nsdManager: NsdManager? = null
    private var nombreServicioPropio: String? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private var callbackEnlace: ((Boolean, String?) -> Unit)? = null

    @Volatile
    private var ultimoEstadoNotificado: Boolean? = null

    @Volatile
    private var ultimaIpNotificada: String? = null

    /**
     * Inicia el ServerSocket(5000) y deja configurados los callbacks para el canal bidireccional.
     */
    fun iniciarServidor(
        obtenerEstadoParaSync: () -> EstadoSincronizadoIoT,
        onMensajeRecibido: (EstadoSincronizadoIoT, String) -> Unit,
        onEstadoConexion: (String) -> Unit
    ) {
        this.proveedorEstadoActual = obtenerEstadoParaSync
        this.callbackMensajeRecibido = onMensajeRecibido
        this.callbackEstadoConexion = onEstadoConexion

        if (servidorJob?.isActive == true) return

        servidorJob = scope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PUERTO_IOT))
                }
                withContext(Dispatchers.Main) {
                    onEstadoConexion("Servicio activo, en espera de enlace")
                }

                while (isActive) {
                    val clienteEntrante = serverSocket?.accept() ?: break
                    // Al aceptar conexión desde el Emulador/Teléfono, mantenemos este mismo Socket abierto en ambos sentidos
                    configurarCanalBidireccional(clienteEntrante, esLadoServidor = true)
                }
            } catch (e: Exception) {
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        onEstadoConexion("Aviso de red: ${e.localizedMessage ?: "Servicio detenido"}")
                    }
                }
            }
        }
    }

    /**
     * Intenta una sincronización automática silenciosa al iniciar la app (incluso antes del Login).
     * Si el Supervisor ya está escuchando en ipDestino, descarga usuarios e historial en segundo plano.
     */
    fun sincronizarSilenciosoAlInicio(ipDestino: String) {
        val ipLimpia = ipDestino.trim()
        val ipPropia = obtenerIpLocal()
        if (ipLimpia.isBlank() || ipLimpia == ipPropia) return

        scope.launch {
            try {
                val actual = socketActivo
                if (actual != null && actual.isConnected && !actual.isClosed) return@launch

                val nuevoSocket = Socket()
                nuevoSocket.connect(InetSocketAddress(ipLimpia, PUERTO_IOT), 1000)
                configurarCanalBidireccional(nuevoSocket, esLadoServidor = false)
            } catch (_: Exception) {
                // Silencioso al arranque si el otro dispositivo aún no está disponible
            }
        }
    }

    /**
     * Abre un Socket TCP persistente hacia el otro dispositivo (ej. desde el Emulador hacia el Hotspot del Teléfono),
     * lo mantiene abierto para lectura/escritura bidireccional y sincroniza el historial SQLite en ambos sentidos.
     */
    fun conectarYSincronizar(
        ipDestino: String,
        onResultado: (mensajeEstado: String, tramaAES: String) -> Unit
    ) {
        val ipLimpia = ipDestino.trim()
        val ipPropia = obtenerIpLocal()
        if (ipLimpia.isBlank() || ipLimpia == ipPropia) {
            onResultado("Ingresa la dirección IP del otro dispositivo", "")
            return
        }

        scope.launch {
            val maxIntentos = 3
            var conectado = false

            for (intento in 1..maxIntentos) {
                try {
                    withContext(Dispatchers.Main) {
                        onResultado(
                            if (intento == 1) "Conectando con $ipLimpia..."
                            else "Reintentando enlace con $ipLimpia ($intento/$maxIntentos)...",
                            ""
                        )
                    }

                    // Si ya hay un socket abierto hacia esa misma IP, enviamos la sincronización por el canal activo
                    val actual = socketActivo
                    val ipSocketActual = actual?.inetAddress?.hostAddress
                    if (actual != null && actual.isConnected && !actual.isClosed && writerActivo != null && ipSocketActual == ipLimpia) {
                        val estadoSync = proveedorEstadoActual?.invoke()
                        if (estadoSync != null) {
                            val trama = empaquetarYCifrar(estadoSync)
                            writerActivo?.println(trama)
                            if (writerActivo?.checkError() == false) {
                                conectado = true
                                withContext(Dispatchers.Main) {
                                    onResultado(
                                        "Enlace activo y sincronizado con $ipLimpia",
                                        trama
                                    )
                                }
                                break
                            }
                        }
                    }

                    // Abrir nuevo Socket persistente
                    val nuevoSocket = Socket()
                    nuevoSocket.connect(InetSocketAddress(ipLimpia, PUERTO_IOT), 2000)
                    configurarCanalBidireccional(nuevoSocket, esLadoServidor = false)

                    val estadoSync = proveedorEstadoActual?.invoke()
                    val tramaEnviada = if (estadoSync != null) empaquetarYCifrar(estadoSync) else ""

                    conectado = true
                    withContext(Dispatchers.Main) {
                        onResultado(
                            "Enlace establecido y sincronizado con $ipLimpia",
                            tramaEnviada
                        )
                    }
                    break
                } catch (e: Exception) {
                    if (intento < maxIntentos) {
                        delay(800)
                    }
                }
            }

            if (!conectado) {
                withContext(Dispatchers.Main) {
                    onResultado(
                        "No se pudo conectar con $ipLimpia tras $maxIntentos intentos",
                        ""
                    )
                }
            }
        }
    }

    /**
     * Mantiene el Socket abierto tanto para escribir (PrintWriter) como para leer (BufferedReader)
     * continuamente, permitiendo comunicación en ambos sentidos sin redirección de puertos en el PC.
     */
    private fun configurarCanalBidireccional(socket: Socket, esLadoServidor: Boolean) {
        try {
            val ipRemota = socket.inetAddress?.hostAddress?.removePrefix("::ffff:") ?: "Dispositivo Remoto"
            val ipPropia = obtenerIpLocal()
            val actual = socketActivo
            // Si acabamos de abrir un enlace saliente como iniciador designado (IP menor), descartar el cruce entrante simultáneo
            if (esLadoServidor &&
                actual != null &&
                actual.isConnected &&
                !actual.isClosed &&
                actual.inetAddress?.hostAddress?.removePrefix("::ffff:") == ipRemota &&
                System.currentTimeMillis() - inicioConexionActualMs < 1500L &&
                compararIp(ipPropia, ipRemota) < 0
            ) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                return
            }

            lectorClienteJob?.cancel()

            // Se asigna primero el nuevo socket para que el lector anterior no anule el writer nuevo al cerrarse
            val socketAnterior = socketActivo
            socket.keepAlive = true
            socket.tcpNoDelay = true
            socketActivo = socket
            enlaceVerificado = false
            inicioConexionActualMs = System.currentTimeMillis()
            ultimaRecepcionMs = inicioConexionActualMs
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
            writerActivo = writer

            if (socketAnterior != null && socketAnterior !== socket) {
                try {
                    socketAnterior.close()
                } catch (_: Exception) {
                }
            }

            val verificado = AtomicBoolean(false)

            // Al establecer el canal, ambos extremos envían automáticamente su estado actual + historial SQLite + usuarios
            val estadoInicial = proveedorEstadoActual?.invoke()
            if (estadoInicial != null) {
                val tramaSync = empaquetarYCifrar(estadoInicial)
                writer.println(tramaSync)
            }

            lectorClienteJob = scope.launch {
                withContext(Dispatchers.Main) {
                    callbackEstadoConexion?.invoke(
                        if (esLadoServidor) "Enlace establecido con dispositivo ($ipRemota)"
                        else "Enlace activo con $ipRemota"
                    )
                }

                try {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    while (isActive && !socket.isClosed) {
                        val lineaCifrada = reader.readLine() ?: break
                        // Cualquier línea (incluido el latido vacío) confirma que el enlace sigue vivo
                        if (socketActivo === socket) {
                            ultimaRecepcionMs = System.currentTimeMillis()
                        }
                        if (lineaCifrada.isNotBlank()) {
                            val estadoRecibido = descifrarYDesempaquetar(lineaCifrada)
                            if (estadoRecibido != null) {
                                // Primera trama AES válida: el otro extremo es AccessGuard IoT → enlace VERIFICADO (verde)
                                if (verificado.compareAndSet(false, true) && socketActivo === socket) {
                                    enlaceVerificado = true
                                    ipEnlaceActual = ipRemota
                                    ipsNoValidas.remove(ipRemota)
                                    ultimaIpExitosa = ipRemota
                                    notificarEnlace(true, ipRemota)
                                }
                                withContext(Dispatchers.Main) {
                                    callbackEstadoConexion?.invoke(
                                        "Sincronizado en tiempo real ($ipRemota)"
                                    )
                                    callbackMensajeRecibido?.invoke(estadoRecibido, ipRemota)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (socketActivo === socket) {
                        writerActivo = null
                        socketActivo = null
                        enlaceVerificado = false
                        ipEnlaceActual = null
                        desconectadoDesdeMs = System.currentTimeMillis()
                        notificarEnlace(false, null)
                        withContext(Dispatchers.Main) {
                            callbackEstadoConexion?.invoke(
                                "Enlace interrumpido. Reconectando automáticamente..."
                            )
                        }
                    }
                }
            }

            // Vigilante: si en pocos segundos no llega una trama AES válida, no es un dispositivo AccessGuard IoT
            scope.launch {
                delay(TIMEOUT_VERIFICACION_MS)
                if (!verificado.get() && socketActivo === socket) {
                    ipsNoValidas.add(ipRemota)
                    try {
                        socket.close()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // =====================================================================================
    // AUTOCONEXIÓN Y AUTODESCUBRIMIENTO EN SEGUNDO PLANO (PUERTO 5000)
    // =====================================================================================

    /** Indica si existe un socket TCP abierto con el otro dispositivo. */
    fun estaConectado(): Boolean {
        val s = socketActivo
        return s != null && s.isConnected && !s.isClosed
    }

    /** Indica si el enlace está abierto Y verificado con una trama AES-128 válida (indicador verde). */
    fun estaEnlazado(): Boolean = enlaceVerificado && estaConectado()

    /** IP del dispositivo enlazado actualmente (o null si está buscando). */
    fun ipEnlazada(): String? = if (estaEnlazado()) ipEnlaceActual else null

    /**
     * Inicia la autoconexión automática: NsdManager + UDP Broadcast + IPs de Hotspot/Emulador
     * + escaneo rápido de la subred local en el puerto 5000. Mantiene el enlace con latidos y reconecta solo.
     * No reemplaza la conexión manual (queda como respaldo).
     */
    fun iniciarAutoConexion(
        obtenerIpPreferida: () -> String,
        onEnlaceCambiado: (conectado: Boolean, ipRemota: String?) -> Unit
    ) {
        callbackEnlace = onEnlaceCambiado
        ultimoEstadoNotificado = null
        notificarEnlace(estaEnlazado(), ipEnlazada())

        if (autoConexionJob?.isActive == true) return

        adquirirMulticastLock()
        iniciarNsd()
        iniciarEscuchaUdp()

        autoConexionJob = scope.launch {
            var ciclo = 0
            var ipsAnteriores: Set<String> = emptySet()
            while (isActive) {
                try {
                    if (estaConectado()) {
                        // Latido (línea vacía) para detectar caídas rápido; el receptor ignora líneas vacías
                        enviarHeartbeat()
                        val sinDatos = System.currentTimeMillis() - ultimaRecepcionMs
                        if (ultimaRecepcionMs > 0 && sinDatos > TIMEOUT_SIN_DATOS_MS) {
                            cerrarSocketActivo()
                        }
                    } else {
                        notificarEnlace(false, null)
                        val interfacesLan = obtenerInterfacesLanActivas()
                        val ipPropia = interfacesLan.firstOrNull()?.ip ?: obtenerIpLocal()
                        val ipsPropias = (interfacesLan.map { it.ip } + obtenerTodasIpsLocales()).toSet()

                        // Si el usuario activó Zona Wi-Fi o cambió de red con la app abierta, limpiar caché de IPs fallidas
                        if (ipsAnteriores.isNotEmpty() && ipsPropias != ipsAnteriores) {
                            ipsNoValidas.clear()
                        }
                        ipsAnteriores = ipsPropias

                        // 1. Anunciarse por UDP Broadcast directamente sobre cada interfaz Wi-Fi / Hotspot activa
                        enviarBroadcastUdp(interfacesLan, ipPropia)

                        // 2. Probar en paralelo: descubiertas (NSD/UDP), gateway Hotspot del sistema, .1 de cada subred, historial y emulador
                        val preferida = try {
                            obtenerIpPreferida()
                        } catch (_: Exception) {
                            ""
                        }
                        val candidatos = construirCandidatos(preferida, ipPropia, ipsPropias, interfacesLan)
                        intentarConexionParalela(candidatos, ipPropia)

                        // 3. Escaneo rápido de todas las subredes Wi-Fi / Hotspot activas cada 2 ciclos
                        if (!estaConectado() && ciclo % 2 == 0) {
                            escanearSubredesLocales(interfacesLan, ipPropia, ipsPropias)
                        }

                        // Las IPs descartadas se vuelven a habilitar tras pocos ciclos (~10 s)
                        if (ciclo % 6 == 5) {
                            ipsNoValidas.clear()
                        }
                        ciclo++
                    }
                } catch (_: Exception) {
                }
                delay(if (estaConectado()) 4000L else 1500L)
            }
        }
    }

    private fun notificarEnlace(activo: Boolean, ip: String?) {
        val cb = callbackEnlace ?: return
        if (ultimoEstadoNotificado == activo && ultimaIpNotificada == ip) return
        ultimoEstadoNotificado = activo
        ultimaIpNotificada = ip
        scope.launch(Dispatchers.Main) {
            cb(activo, ip)
        }
    }

    private fun enviarHeartbeat() {
        val w = writerActivo ?: return
        try {
            w.println("")
            if (w.checkError()) {
                cerrarSocketActivo()
            }
        } catch (_: Exception) {
            cerrarSocketActivo()
        }
    }

    private fun cerrarSocketActivo() {
        try {
            socketActivo?.close()
        } catch (_: Exception) {
        }
    }

    private fun construirCandidatos(
        ipPreferida: String,
        ipPropia: String,
        ipsPropias: Set<String>,
        interfacesLan: List<InterfazLanLocal>
    ): List<String> {
        val lista = LinkedHashSet<String>()
        lista.addAll(ipsDescubiertas)
        lista.addAll(obtenerGatewaysDesdeSistema())
        for (iface in interfacesLan) {
            prefijoSubred(iface.ip)?.let { lista.add("$it.1") }
        }
        prefijoSubred(ipPropia)?.let { lista.add("$it.1") }
        ultimaIpExitosa?.let { lista.add(it) }
        ipPreferida.trim().takeIf { it.isNotBlank() }?.let { lista.add(it) }
        lista.addAll(IPS_HOTSPOT_EMULADOR)
        lista.remove("127.0.0.1")
        lista.removeAll(ipsPropias)
        lista.removeAll(ipsNoValidas)
        return lista.toList()
    }

    /** Desempate determinista: la IP menor conecta de inmediato, la mayor solo tras ESPERA_DESEMPATE_MS. */
    private fun puedeIniciarConexion(ipRemota: String, ipPropia: String): Boolean {
        if (ipRemota == ipPropia) return false
        if (compararIp(ipPropia, ipRemota) < 0) return true
        return System.currentTimeMillis() - desconectadoDesdeMs > ESPERA_DESEMPATE_MS
    }

    private fun compararIp(a: String, b: String): Int {
        val na = ipALong(a)
        val nb = ipALong(b)
        return if (na != null && nb != null) na.compareTo(nb) else a.compareTo(b)
    }

    private fun ipALong(ip: String): Long? {
        val partes = ip.trim().split(".")
        if (partes.size != 4) return null
        var valor = 0L
        for (p in partes) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            valor = valor * 256 + n
        }
        return valor
    }

    private fun esIpv4Privada(ip: String): Boolean {
        val partes = ip.split(".")
        if (partes.size != 4) return false
        val p0 = partes[0].toIntOrNull() ?: return false
        val p1 = partes[1].toIntOrNull() ?: return false
        return p0 == 10 || (p0 == 192 && p1 == 168) || (p0 == 172 && p1 in 16..31)
    }

    private fun prefijoSubred(ip: String): String? {
        if (!esIpv4Privada(ip)) return null
        val partes = ip.split(".")
        return "${partes[0]}.${partes[1]}.${partes[2]}"
    }

    /** Prueba todos los candidatos prioritarios en paralelo (~500 ms totales en lugar de esperar uno por uno). */
    private suspend fun intentarConexionParalela(candidatos: List<String>, ipPropia: String) {
        if (candidatos.isEmpty() || estaConectado()) return
        val filtrados = candidatos.filter { puedeIniciarConexion(it, ipPropia) }
        if (filtrados.isEmpty()) return

        coroutineScope {
            val sockets = filtrados.map { ip ->
                async(Dispatchers.IO) {
                    if (estaConectado()) return@async null
                    val s = Socket()
                    try {
                        s.connect(InetSocketAddress(ip, PUERTO_IOT), TIMEOUT_CONEXION_AUTO_MS)
                        s
                    } catch (_: Exception) {
                        try {
                            s.close()
                        } catch (_: Exception) {
                        }
                        null
                    }
                }
            }.awaitAll().filterNotNull()

            var adoptado = false
            for (s in sockets) {
                if (adoptado) {
                    try {
                        s.close()
                    } catch (_: Exception) {
                    }
                } else {
                    adoptado = adoptarSocketSiLibre(s)
                }
            }
        }
    }

    private fun intentarConexionAuto(ip: String, timeoutMs: Int): Boolean {
        if (estaConectado()) return false
        val s = Socket()
        return try {
            s.connect(InetSocketAddress(ip, PUERTO_IOT), timeoutMs)
            adoptarSocketSiLibre(s)
        } catch (_: Exception) {
            try {
                s.close()
            } catch (_: Exception) {
            }
            false
        }
    }

    /** Usa el socket como canal bidireccional solo si aún no existe un enlace (evita duplicados). */
    private fun adoptarSocketSiLibre(s: Socket): Boolean {
        synchronized(lockConexion) {
            if (estaConectado()) {
                try {
                    s.close()
                } catch (_: Exception) {
                }
                return false
            }
            configurarCanalBidireccional(s, esLadoServidor = false)
            return true
        }
    }

    /** Escaneo rápido y paralelo (bloques de 64 hosts, 350 ms) de todas las subredes Wi-Fi/Hotspot activas. */
    private suspend fun escanearSubredesLocales(
        interfacesLan: List<InterfazLanLocal>,
        ipPropia: String,
        ipsPropias: Set<String>
    ) {
        val prefijos = linkedSetOf<String>()
        for (iface in interfacesLan) {
            prefijoSubred(iface.ip)?.let { prefijos.add(it) }
        }
        prefijoSubred(ipPropia)?.let { prefijos.add(it) }
        if (prefijos.isEmpty()) return

        for (prefijo in prefijos) {
            if (estaConectado()) break
            val hosts = (1..254).map { "$prefijo.$it" }
                .filter { it !in ipsPropias && it !in ipsNoValidas }

            coroutineScope {
                for (bloque in hosts.chunked(64)) {
                    if (estaConectado()) break
                    val encontrados = bloque.map { ip ->
                        async(Dispatchers.IO) {
                            if (estaConectado() || !puedeIniciarConexion(ip, ipPropia)) return@async null
                            val s = Socket()
                            try {
                                s.connect(InetSocketAddress(ip, PUERTO_IOT), TIMEOUT_ESCANEO_MS)
                                s
                            } catch (_: Exception) {
                                try {
                                    s.close()
                                } catch (_: Exception) {
                                }
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()

                    var adoptado = false
                    for (s in encontrados) {
                        if (adoptado) {
                            try {
                                s.close()
                            } catch (_: Exception) {
                            }
                        } else {
                            adoptado = adoptarSocketSiLibre(s)
                        }
                    }
                    if (adoptado) break
                }
            }
        }
    }

    private fun registrarIpDescubierta(ip: String) {
        val ipsPropias = obtenerTodasIpsLocales()
        if (ip.isBlank() || ip == "127.0.0.1" || ip in ipsPropias) return
        ipsDescubiertas.add(ip)
        ipsNoValidas.remove(ip)
        if (!estaConectado()) {
            scope.launch {
                if (!estaConectado() && puedeIniciarConexion(ip, obtenerIpLocal())) {
                    intentarConexionAuto(ip, 1200)
                }
            }
        }
    }

    // ---------------------------- UDP Broadcast (Puerto 5001) ----------------------------
    private fun iniciarEscuchaUdp() {
        if (udpEscuchaJob?.isActive == true) return
        udpEscuchaJob = scope.launch {
            try {
                val socketUdp = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(PUERTO_DESCUBRIMIENTO_UDP))
                }
                socketUdpEscucha = socketUdp
                val buffer = ByteArray(512)
                while (isActive && !socketUdp.isClosed) {
                    val paquete = DatagramPacket(buffer, buffer.size)
                    socketUdp.receive(paquete)
                    val mensaje = String(paquete.data, 0, paquete.length, Charsets.UTF_8)
                    val partes = mensaje.split("|")
                    if (partes.size < 3 || partes[0] != FIRMA_UDP) continue

                    // El anuncio viene cifrado con AES-128: solo se aceptan dispositivos AccessGuard IoT legítimos
                    val token = SecurityUtils.descifrarAES(partes[2]) ?: continue
                    if (!token.startsWith(TOKEN_DESCUBRIMIENTO)) continue

                    val ipOrigen = paquete.address?.hostAddress?.removePrefix("::ffff:") ?: continue
                    registrarIpDescubierta(ipOrigen)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun enviarBroadcastUdp(interfacesLan: List<InterfazLanLocal>, ipPropia: String) {
        try {
            val token = SecurityUtils.cifrarAES("$TOKEN_DESCUBRIMIENTO|$ipPropia")
            val datos = "$FIRMA_UDP|$PUERTO_IOT|$token".toByteArray(Charsets.UTF_8)

            // 1. Enviar enlazado a cada interfaz Wi-Fi / Hotspot para evitar que Android lo desvíe por datos móviles (4G/5G)
            for (iface in interfacesLan) {
                val destinos = linkedSetOf<String>()
                iface.broadcast?.let { destinos.add(it) }
                prefijoSubred(iface.ip)?.let { destinos.add("$it.255") }
                destinos.add("255.255.255.255")
                try {
                    DatagramSocket(InetSocketAddress(iface.direccion, 0)).use { s ->
                        s.broadcast = true
                        for (destino in destinos) {
                            try {
                                s.send(
                                    DatagramPacket(
                                        datos, datos.size,
                                        InetAddress.getByName(destino), PUERTO_DESCUBRIMIENTO_UDP
                                    )
                                )
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // 2. Respaldo general si no hubo interfaz LAN específica (ej. puente de emulador)
            DatagramSocket().use { s ->
                s.broadcast = true
                val destinos = linkedSetOf("255.255.255.255")
                prefijoSubred(ipPropia)?.let { destinos.add("$it.255") }
                for (destino in destinos) {
                    try {
                        s.send(
                            DatagramPacket(
                                datos, datos.size,
                                InetAddress.getByName(destino), PUERTO_DESCUBRIMIENTO_UDP
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun adquirirMulticastLock() {
        val ctx = appContext ?: return
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wm.createMulticastLock("AccessGuardIoT-Descubrimiento").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------- NsdManager (mDNS / DNS-SD) ----------------------------
    private fun iniciarNsd() {
        val ctx = appContext ?: return
        if (nsdManager != null) return
        try {
            val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = nsd

            val info = NsdServiceInfo().apply {
                serviceName = "AccessGuardIoT-${(1000..9999).random()}"
                serviceType = TIPO_SERVICIO_NSD
                port = PUERTO_IOT
            }

            val regListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    nombreServicioPropio = serviceInfo.serviceName
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            registrationListener = regListener
            nombreServicioPropio = info.serviceName
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)

            val discListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceName == nombreServicioPropio) return
                    if (!serviceInfo.serviceType.contains("_accessguard")) return
                    resolverServicioNsd(serviceInfo)
                }
            }
            discoveryListener = discListener
            nsd.discoverServices(TIPO_SERVICIO_NSD, NsdManager.PROTOCOL_DNS_SD, discListener)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun resolverServicioNsd(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host ?: return
                    if (host !is Inet4Address) return
                    val ip = host.hostAddress ?: return
                    registrarIpDescubierta(ip)
                }
            })
        } catch (_: Exception) {
        }
    }

    /** Todas las IPv4 propias (un teléfono con Zona Wi-Fi puede tener varias interfaces activas). */
    private fun obtenerTodasIpsLocales(): Set<String> {
        val resultado = mutableSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val red = interfaces.nextElement()
                if (!red.isUp) continue
                val direcciones = red.inetAddresses
                while (direcciones.hasMoreElements()) {
                    val addr = direcciones.nextElement()
                    if (addr is Inet4Address) {
                        addr.hostAddress?.let { resultado.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        resultado.add(obtenerIpLocal())
        return resultado
    }

    /**
     * Envía un movimiento en tiempo real + los últimos eventos SQLite por el Socket bidireccional abierto.
     * Si el canal aún no estaba abierto, intenta conectarse automáticamente a ipDestino.
     */
    fun enviarCambio(
        ipDestino: String,
        estado: EstadoSincronizadoIoT,
        onEstadoEnvio: (mensajeEstado: String, tramaAES: String) -> Unit
    ) {
        val tramaCifrada = empaquetarYCifrar(estado)

        scope.launch {
            // 1. Intentar enviar primero por el Socket bidireccional que ya está abierto
            val socketActual = socketActivo
            val writerActual = writerActivo
            if (socketActual != null && socketActual.isConnected && !socketActual.isClosed && writerActual != null) {
                try {
                    writerActual.println(tramaCifrada)
                    if (!writerActual.checkError()) {
                        val ipRemota = socketActual.inetAddress?.hostAddress ?: ipDestino
                        withContext(Dispatchers.Main) {
                            onEstadoEnvio(
                                "Sincronizado en tiempo real ($ipRemota)",
                                tramaCifrada
                            )
                        }
                        return@launch
                    }
                } catch (_: Exception) {
                }
            }

            // 2. Si no había un canal abierto, conectar a ipDestino (o última IP descubierta) con reintento automático
            val ipLimpia = ipDestino.trim().ifBlank { ultimaIpExitosa ?: "" }
            val ipPropia = obtenerIpLocal()
            if (ipLimpia.isBlank() || ipLimpia == ipPropia) {
                withContext(Dispatchers.Main) {
                    onEstadoEnvio(
                        "Guardado localmente (En espera de conexión)",
                        tramaCifrada
                    )
                }
                return@launch
            }

            val maxIntentos = 3
            var enviado = false

            for (intento in 1..maxIntentos) {
                try {
                    if (intento > 1) {
                        withContext(Dispatchers.Main) {
                            onEstadoEnvio(
                                "Reintentando enlace con $ipLimpia ($intento/$maxIntentos)...",
                                tramaCifrada
                            )
                        }
                    }

                    val nuevoSocket = Socket()
                    nuevoSocket.connect(InetSocketAddress(ipLimpia, PUERTO_IOT), 1500)
                    configurarCanalBidireccional(nuevoSocket, esLadoServidor = false)

                    // Enviar también el evento específico recién ocurrido
                    writerActivo?.println(tramaCifrada)
                    enviado = true
                    withContext(Dispatchers.Main) {
                        onEstadoEnvio(
                            "Conectado y sincronizado con $ipLimpia",
                            tramaCifrada
                        )
                    }
                    break
                } catch (_: Exception) {
                    if (intento < maxIntentos) {
                        delay(800)
                    }
                }
            }

            if (!enviado) {
                withContext(Dispatchers.Main) {
                    onEstadoEnvio(
                        "Sin enlace con $ipLimpia (Se sincronizará automáticamente al reconectar)",
                        tramaCifrada
                    )
                }
            }
        }
    }

    /**
     * Empaqueta el estado actual y la lista de eventos de SQLite en una sola trama y la cifra con AES-128.
     */
    fun empaquetarYCifrar(estado: EstadoSincronizadoIoT): String {
        val historialTexto = estado.historialEventos.joinToString(";;") { ev ->
            val fechaLimpia = ev.fechaHora.replace("|", "").replace("~", "").replace(";", "")
            val rolLimpio = ev.usuarioRol.replace("|", "").replace("~", "").replace(";", "")
            val detalleLimpio = ev.detalle.replace("|", "").replace("~", "").replace(";", "")
            "$fechaLimpia~$rolLimpio~$detalleLimpio"
        }

        val operadoresTexto = estado.operadoresRegistrados.joinToString(";;") { op ->
            val uLimpio = op.username.replace("|", "").replace("~", "").replace(";", "")
            val pLimpia = op.password.replace("|", "").replace("~", "").replace(";", "")
            "$uLimpio~$pLimpia"
        }

        val tramaPlana = listOf(
            estado.puertaAbierta.toString(),
            estado.anguloServo.toString(),
            estado.buzzerActivo.toString(),
            estado.modoAcceso,
            estado.uidRfid,
            estado.pinBotonera,
            estado.rolEmisor,
            estado.detalleEvento.replace("|", " "),
            estado.timestampModificacion.toString(),
            estado.esSincronizacionInicial.toString(),
            historialTexto,
            operadoresTexto,
            estado.timestampOperadores.toString(),
            estado.claveSupervisor.replace("|", ""),
            estado.codigoAuthHash.replace("|", ""),
            estado.codigoAuthCifrado.replace("|", ""),
            estado.timestampCodigoAuth.toString()
        ).joinToString("|")

        return SecurityUtils.cifrarAES(tramaPlana)
    }

    /**
     * Descifra la trama AES-128 y reconstruye el estado junto con el historial de eventos SQLite.
     */
    private fun descifrarYDesempaquetar(lineaCifrada: String): EstadoSincronizadoIoT? {
        val tramaPlana = SecurityUtils.descifrarAES(lineaCifrada) ?: return null
        val partes = tramaPlana.split("|")
        if (partes.size < 8) return null

        val timestamp = partes.getOrNull(8)?.toLongOrNull() ?: 0L
        val esSyncInicial = partes.getOrNull(9)?.toBoolean() ?: false
        val historialRaw = partes.getOrNull(10) ?: ""
        val operadoresRaw = partes.getOrNull(11) ?: ""
        val timestampOperadores = partes.getOrNull(12)?.toLongOrNull() ?: 0L
        val claveSupervisor = partes.getOrNull(13) ?: ""
        val codigoAuthHash = partes.getOrNull(14) ?: ""
        val codigoAuthCifrado = partes.getOrNull(15) ?: ""
        val timestampCodigoAuth = partes.getOrNull(16)?.toLongOrNull() ?: 0L

        val listaHistorial = if (historialRaw.isBlank()) {
            emptyList()
        } else {
            historialRaw.split(";;").mapNotNull { item ->
                val campos = item.split("~")
                if (campos.size >= 3) {
                    EventoBitacora(
                        id = 0,
                        fechaHora = campos[0],
                        usuarioRol = campos[1],
                        detalle = campos[2]
                    )
                } else null
            }
        }

        val listaOperadores = if (operadoresRaw.isBlank()) {
            emptyList()
        } else {
            operadoresRaw.split(";;").mapNotNull { item ->
                val campos = item.split("~")
                if (campos.size >= 2) {
                    UsuarioOperador(
                        username = campos[0],
                        password = campos[1]
                    )
                } else null
            }
        }

        return EstadoSincronizadoIoT(
            puertaAbierta = partes[0].toBoolean(),
            anguloServo = partes[1].toIntOrNull() ?: 0,
            buzzerActivo = partes[2].toBoolean(),
            modoAcceso = partes[3],
            uidRfid = partes[4],
            pinBotonera = partes[5],
            rolEmisor = partes[6],
            detalleEvento = partes[7],
            timestampModificacion = timestamp,
            esSincronizacionInicial = esSyncInicial,
            historialEventos = listaHistorial,
            operadoresRegistrados = listaOperadores,
            timestampOperadores = timestampOperadores,
            claveSupervisor = claveSupervisor,
            tramaCifradaAES = lineaCifrada,
            codigoAuthHash = codigoAuthHash,
            codigoAuthCifrado = codigoAuthCifrado,
            timestampCodigoAuth = timestampCodigoAuth
        )
    }

    /** Obtiene interfaces locales Wi-Fi / Hotspot / Ethernet válidas con su IPv4 privada y dirección broadcast real. */
    private fun obtenerInterfacesLanActivas(): List<InterfazLanLocal> {
        val resultado = mutableListOf<InterfazLanLocal>()
        val prefijosExcluidos = listOf(
            "rmnet", "ccmni", "pdp", "seth", "epdg", "v4-",
            "tun", "tap", "ppp", "dummy", "clat", "wwan", "sit", "ip6tnl"
        )
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val red = interfaces.nextElement()
                if (!red.isUp || red.isLoopback) continue
                val nombre = red.name.lowercase()
                if (prefijosExcluidos.any { nombre.contains(it) }) continue

                val prioridad = when {
                    nombre.contains("swlan") || nombre.contains("ap") ||
                        nombre.contains("softap") || nombre.contains("tether") ||
                        nombre.contains("rndis") -> 3
                    nombre.contains("wlan") || nombre.contains("eth") ||
                        nombre.contains("en") || nombre.contains("bridge") -> 2
                    else -> 1
                }

                for (ifAddr in red.interfaceAddresses) {
                    val addr = ifAddr.address as? Inet4Address ?: continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip == "0.0.0.0" || !esIpv4Privada(ip)) continue

                    val broadcast = ifAddr.broadcast?.hostAddress?.takeIf {
                        it.isNotBlank() && it != "0.0.0.0"
                    }
                    resultado.add(
                        InterfazLanLocal(
                            nombre = nombre,
                            direccion = addr,
                            ip = ip,
                            broadcast = broadcast,
                            prioridad = prioridad
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return resultado.sortedWith(
            compareByDescending<InterfazLanLocal> { it.prioridad }
                .thenByDescending { it.ip.startsWith("192.168.") }
        )
    }

    /** Consulta al sistema los gateways IPv4 de redes Wi-Fi / Ethernet activas (ideal para detectar Zona Wi-Fi). */
    @Suppress("DEPRECATION")
    private fun obtenerGatewaysDesdeSistema(): List<String> {
        val ctx = appContext ?: return emptyList()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
        val gateways = linkedSetOf<String>()
        try {
            val redes = linkedSetOf<android.net.Network>()
            cm.activeNetwork?.let { redes.add(it) }
            for (net in cm.allNetworks) {
                redes.add(net)
            }
            for (net in redes) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                val esRedLocal = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!esRedLocal) continue

                val linkProps = cm.getLinkProperties(net) ?: continue
                for (ruta in linkProps.routes) {
                    val gw = ruta.gateway as? Inet4Address ?: continue
                    if (gw.isLoopbackAddress) continue
                    val gwIp = gw.hostAddress ?: continue
                    if (gwIp != "0.0.0.0" && esIpv4Privada(gwIp)) {
                        gateways.add(gwIp)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return gateways.toList()
    }

    /**
     * Recorre las interfaces de red detectando primero la IP real de Zona Wi-Fi (Hotspot),
     * Wi-Fi local o Emulador, evitando confundirse con interfaces celulares (rmnet) o VPN.
     */
    fun obtenerIpLocal(): String {
        obtenerInterfacesLanActivas().firstOrNull()?.let { return it.ip }
        return try {
            val candidatas = mutableListOf<Pair<String, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val red = interfaces.nextElement()
                if (!red.isUp || red.isLoopback) continue
                val nombreRed = red.name.lowercase()
                val direcciones = red.inetAddresses
                while (direcciones.hasMoreElements()) {
                    val addr = direcciones.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip != "0.0.0.0") {
                            candidatas.add(nombreRed to ip)
                        }
                    }
                }
            }
            candidatas.firstOrNull { it.second.startsWith("192.168.") }?.let { return it.second }
            candidatas.firstOrNull {
                it.second.startsWith("172.") || it.second.startsWith("10.")
            }?.let { return it.second }
            candidatas.firstOrNull()?.second ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    /**
     * Cierra los sockets activos y libera recursos de autodescubrimiento al destruir la actividad.
     */
    fun detenerServidor() {
        autoConexionJob?.cancel()
        udpEscuchaJob?.cancel()
        lectorClienteJob?.cancel()
        servidorJob?.cancel()
        callbackEnlace = null
        enlaceVerificado = false
        ipEnlaceActual = null

        try {
            socketUdpEscucha?.close()
        } catch (_: Exception) {
        }
        socketUdpEscucha = null

        val nsd = nsdManager
        if (nsd != null) {
            discoveryListener?.let { listener ->
                try {
                    nsd.stopServiceDiscovery(listener)
                } catch (_: Exception) {
                }
            }
            registrationListener?.let { listener ->
                try {
                    nsd.unregisterService(listener)
                } catch (_: Exception) {
                }
            }
        }
        discoveryListener = null
        registrationListener = null
        nsdManager = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
        multicastLock = null

        try {
            socketActivo?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }
}
