package com.example.accessguardiot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accessguardiot.ui.theme.AccessGuardIoTTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Actividad Principal con navegación y pantallas en Jetpack Compose (Material 3)
 */
class MainActivity : ComponentActivity() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var wifiManager: WifiSocketManager
    private lateinit var esp32Manager: Esp32SocketManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        dbHelper = DatabaseHelper(this)
        wifiManager = WifiSocketManager(this)
        esp32Manager = Esp32SocketManager(this)

        setContent {
            AccessGuardIoTTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AccessGuardApp(
                        dbHelper = dbHelper,
                        wifiManager = wifiManager,
                        esp32Manager = esp32Manager,
                        modifier = Modifier
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                            .imePadding()
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiManager.detenerServidor()
        esp32Manager.detener()
    }
}

/**
 * Desplaza suavemente el campo enfocado para que no quede cubierto al animarse el teclado en pantalla.
 */
@Composable
private fun rememberScrollAlEnfocarModifier(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return Modifier
        .bringIntoViewRequester(requester)
        .onFocusEvent { state ->
            if (state.isFocused) {
                scope.launch {
                    delay(220)
                    requester.bringIntoView()
                }
            }
        }
}

@Composable
fun AccessGuardApp(
    dbHelper: DatabaseHelper,
    wifiManager: WifiSocketManager,
    esp32Manager: Esp32SocketManager,
    modifier: Modifier = Modifier
) {
    // Cargar estado persistido en SQLite (por si la app se cerró y se volvió a abrir)
    val estadoInicialSQLite = remember { dbHelper.obtenerEstadoPanel() }

    // Rol y usuario autenticado actual: null = LoginScreen, "SUPERVISOR" u "OPERADOR" = DashboardScreen
    var rolActivo by rememberSaveable { mutableStateOf<String?>(null) }
    var usuarioActivo by rememberSaveable { mutableStateOf<String?>(null) }
    var avisoSesionRevocada by rememberSaveable { mutableStateOf<String?>(null) }

    // Estados en vivo del cerrojo IoT (inicializados desde SQLite y sincronizados por Wi-Fi)
    var puertaAbierta by remember { mutableStateOf(estadoInicialSQLite.puertaAbierta) }
    var anguloServo by remember { mutableIntStateOf(estadoInicialSQLite.anguloServo) }
    var buzzerActivo by remember { mutableStateOf(estadoInicialSQLite.buzzerActivo) }
    var modoAcceso by remember { mutableStateOf(estadoInicialSQLite.modoAcceso) } // "RFID", "BOTONERA", "RFID_PIN" o "LOCKDOWN"
    var uidRfid by remember { mutableStateOf(estadoInicialSQLite.uidRfid) }
    var pinBotonera by remember { mutableStateOf(estadoInicialSQLite.pinBotonera) }
    var timestampModificacion by remember { mutableLongStateOf(estadoInicialSQLite.timestampModificacion) }
    var timestampOperadores by remember { mutableLongStateOf(estadoInicialSQLite.timestampOperadores) }
    var codigoAutorizacionActual by remember { mutableStateOf(dbHelper.obtenerCodigoAutorizacionVisible()) }

    // Estados de red Wi-Fi / Hotspot (Puerto 5000 entre celulares) y cifrado AES-128
    var ipLocal by remember { mutableStateOf(wifiManager.obtenerIpLocal()) }
    var ipDestino by remember { mutableStateOf(estadoInicialSQLite.ultimaIpDestino) }
    var estadoConexion by remember { mutableStateOf("Servicio activo, en espera de enlace") }
    var enlaceActivo by remember { mutableStateOf(wifiManager.estaEnlazado()) }
    var ipEnlazada by remember { mutableStateOf(wifiManager.ipEnlazada()) }
    var ultimaTramaAES by remember {
        mutableStateOf(
            SecurityUtils.cifrarAES(
                "${estadoInicialSQLite.puertaAbierta}|${estadoInicialSQLite.anguloServo}|" +
                        "${estadoInicialSQLite.buzzerActivo}|${estadoInicialSQLite.modoAcceso}|" +
                        "${estadoInicialSQLite.uidRfid}|${estadoInicialSQLite.pinBotonera}|SISTEMA|Inicio"
            )
        )
    }

    // Estados de enlace hardware ESP32 (Puerto 5002 en paralelo al puerto 5000)
    var ipEsp32 by remember { mutableStateOf(estadoInicialSQLite.ultimaIpEsp32) }
    var esp32Conectado by remember { mutableStateOf(esp32Manager.estaConectado()) }
    var esp32Endpoint by remember { mutableStateOf<String?>(null) }
    var estadoConexionEsp32 by remember {
        mutableStateOf("Escuchando en puerto ${Esp32Protocol.PUERTO_ESP32}")
    }
    var ultimaTramaEsp32 by remember { mutableStateOf("Sin tramas recibidas aún") }

    // Lista de bitácora y operadores cargados desde SQLite
    var listaEventos by remember { mutableStateOf(dbHelper.obtenerEventos()) }
    var listaOperadores by remember { mutableStateOf(dbHelper.obtenerOperadores()) }

    fun etiquetaActor(rol: String?, usuario: String?): String {
        val rolLimpio = (rol ?: "SISTEMA").replace("|", "").replace("~", "").replace(";", "")
        val userLimpio = usuario?.trim()?.takeIf { it.isNotBlank() }
            ?.replace("|", "")?.replace("~", "")?.replace(";", "")
        return if (userLimpio != null) "$rolLimpio ($userLimpio)" else rolLimpio
    }

    // Función que construye el paquete completo (estado actual + historial SQLite + usuarios) para sincronización
    fun construirEstadoParaSync(
        esSyncInicial: Boolean = true,
        rolEmisor: String = etiquetaActor(rolActivo, usuarioActivo),
        detalle: String = "Sincronización de estado e historial"
    ): EstadoSincronizadoIoT {
        val (hashAuth, cifradoAuth, tsAuth) = dbHelper.obtenerDatosSyncCodigoAuth()
        return EstadoSincronizadoIoT(
            puertaAbierta = puertaAbierta,
            anguloServo = anguloServo,
            buzzerActivo = buzzerActivo,
            modoAcceso = modoAcceso,
            uidRfid = uidRfid,
            pinBotonera = pinBotonera,
            rolEmisor = rolEmisor,
            detalleEvento = detalle,
            timestampModificacion = timestampModificacion,
            esSincronizacionInicial = esSyncInicial,
            historialEventos = dbHelper.obtenerEventosParaSincronizar(30),
            operadoresRegistrados = dbHelper.obtenerOperadores(),
            timestampOperadores = timestampOperadores,
            claveSupervisor = dbHelper.obtenerPasswordSupervisor(),
            codigoAuthHash = hashAuth,
            codigoAuthCifrado = cifradoAuth,
            timestampCodigoAuth = tsAuth
        )
    }

    // Función auxiliar que guarda cada movimiento en SQLite, lo envía por Wi-Fi (5000) y al ESP32 (5002)
    fun registrarYSincronizar(
        nuevaPuerta: Boolean = puertaAbierta,
        nuevoAngulo: Int = anguloServo,
        nuevoBuzzer: Boolean = buzzerActivo,
        nuevoModo: String = modoAcceso,
        nuevoUid: String = uidRfid,
        nuevoPin: String = pinBotonera,
        esCambioOperadores: Boolean = false,
        detalleAccion: String,
        rolOverride: String? = null,
        enviarAEsp32: Boolean = true
    ) {
        val actor = rolOverride ?: etiquetaActor(rolActivo, usuarioActivo)
        val ahora = System.currentTimeMillis()

        puertaAbierta = nuevaPuerta
        anguloServo = nuevoAngulo
        buzzerActivo = nuevoBuzzer
        modoAcceso = nuevoModo
        uidRfid = nuevoUid
        pinBotonera = nuevoPin
        timestampModificacion = ahora
        if (esCambioOperadores) {
            timestampOperadores = ahora
        }

        // 1. Guardar evento y estado actual en SQLite local (acceso_iot.db)
        dbHelper.insertarEvento(actor, detalleAccion)
        dbHelper.guardarEstadoPanel(
            puertaAbierta = nuevaPuerta,
            anguloServo = nuevoAngulo,
            buzzerActivo = nuevoBuzzer,
            modoAcceso = nuevoModo,
            uidRfid = nuevoUid,
            pinBotonera = nuevoPin,
            timestampModificacion = ahora,
            ultimaIp = ipDestino,
            timestampOperadores = timestampOperadores
        )
        listaEventos = dbHelper.obtenerEventos()
        listaOperadores = dbHelper.obtenerOperadores()

        // 2. Enviar trama cifrada AES-128 por socket bidireccional (Puerto 5000)
        val (hashAuth, cifradoAuth, tsAuth) = dbHelper.obtenerDatosSyncCodigoAuth()
        val estadoActual = EstadoSincronizadoIoT(
            puertaAbierta = nuevaPuerta,
            anguloServo = nuevoAngulo,
            buzzerActivo = nuevoBuzzer,
            modoAcceso = nuevoModo,
            uidRfid = nuevoUid,
            pinBotonera = nuevoPin,
            rolEmisor = actor,
            detalleEvento = detalleAccion,
            timestampModificacion = ahora,
            esSincronizacionInicial = false,
            historialEventos = dbHelper.obtenerEventosParaSincronizar(30),
            operadoresRegistrados = listaOperadores,
            timestampOperadores = timestampOperadores,
            claveSupervisor = dbHelper.obtenerPasswordSupervisor(),
            codigoAuthHash = hashAuth,
            codigoAuthCifrado = cifradoAuth,
            timestampCodigoAuth = tsAuth
        )
        wifiManager.enviarCambio(ipDestino, estadoActual) { mensajeRed, tramaAES ->
            estadoConexion = mensajeRed
            if (tramaAES.isNotBlank()) {
                ultimaTramaAES = tramaAES
            }
        }

        // 3. Replicar estado físico al ESP32 conectado (Puerto 5002)
        if (enviarAEsp32 && esp32Manager.estaConectado()) {
            esp32Manager.enviarEstado(
                puertaAbierta = nuevaPuerta,
                anguloServo = nuevoAngulo,
                buzzerActivo = nuevoBuzzer,
                modoAcceso = nuevoModo,
                uidRfid = nuevoUid,
                pinBotonera = nuevoPin
            )
        }
    }

    // Registra un evento de seguridad en bitácora sin alterar controles físicos (ej. bloqueo o desbloqueo en Login)
    fun sincronizarSoloBitacoraSeguridad(detalle: String, incluirCambioAuth: Boolean = false) {
        dbHelper.insertarEvento("SEGURIDAD", detalle)
        listaEventos = dbHelper.obtenerEventos()
        if (incluirCambioAuth) {
            timestampOperadores = System.currentTimeMillis()
            dbHelper.guardarEstadoPanel(
                puertaAbierta, anguloServo, buzzerActivo, modoAcceso,
                uidRfid, pinBotonera, timestampModificacion, ipDestino, timestampOperadores
            )
        }
        if (wifiManager.estaEnlazado()) {
            val estadoSync = construirEstadoParaSync(
                esSyncInicial = !incluirCambioAuth,
                rolEmisor = "SEGURIDAD",
                detalle = detalle
            )
            wifiManager.enviarCambio(ipDestino, estadoSync) { mensajeRed, tramaAES ->
                estadoConexion = mensajeRed
                if (tramaAES.isNotBlank()) ultimaTramaAES = tramaAES
            }
        }
    }

    // Procesa tramas de sensores o cambios físicos enviados desde el ESP32
    fun procesarMensajeHardwareEsp32(mensaje: Esp32Protocol.MensajeEsp32) {
        when (mensaje) {
            is Esp32Protocol.MensajeEsp32.Estado -> {
                val puerta = mensaje.puertaAbierta ?: puertaAbierta
                val angulo = mensaje.anguloServo ?: if (puerta) 90 else 0
                val buzzer = mensaje.buzzerActivo ?: buzzerActivo
                val modo = mensaje.modoAcceso ?: modoAcceso
                val det = mensaje.detalle ?: "actualizó estado físico desde ESP32"
                val rolEvento = if (det.contains("denegado", ignoreCase = true) ||
                    det.contains("no autorizad", ignoreCase = true) ||
                    det.contains("incorrecto", ignoreCase = true) ||
                    det.contains("lockdown", ignoreCase = true)
                ) "SEGURIDAD" else "ESP32 (Hardware)"
                registrarYSincronizar(
                    nuevaPuerta = puerta,
                    nuevoAngulo = angulo,
                    nuevoBuzzer = buzzer,
                    nuevoModo = modo,
                    detalleAccion = det,
                    rolOverride = rolEvento,
                    enviarAEsp32 = false
                )
            }
            is Esp32Protocol.MensajeEsp32.TarjetaRfid -> {
                if (modoAcceso == "LOCKDOWN") {
                    esp32Manager.enviarLinea("ERR|LOCKDOWN")
                    sincronizarSoloBitacoraSeguridad("Tarjeta RFID rechazada en ESP32: modo LOCKDOWN activo")
                } else if (modoAcceso == "RFID" || modoAcceso == "RFID_PIN") {
                    val uidNormalizado = mensaje.uid.replace(" ", "-")
                    val uidPanelNormalizado = uidRfid.replace(" ", "-")
                    val uidEnmascarado = "****-${uidNormalizado.takeLast(2)}"
                    if (uidNormalizado.equals(uidPanelNormalizado, ignoreCase = true)) {
                        registrarYSincronizar(
                            nuevaPuerta = true,
                            nuevoAngulo = 90,
                            nuevoBuzzer = false,
                            detalleAccion = "abrió puerta con tarjeta RFID autorizada ($uidEnmascarado)",
                            rolOverride = "ESP32 (RFID)"
                        )
                        esp32Manager.enviarLinea("OK|PUERTA=ABIERTA")
                    } else {
                        registrarYSincronizar(
                            nuevoBuzzer = true,
                            detalleAccion = "Tarjeta RFID no autorizada detectada en ESP32 ($uidEnmascarado)",
                            rolOverride = "SEGURIDAD"
                        )
                        esp32Manager.enviarLinea("ERR|RFID_INVALIDO")
                    }
                }
            }
            is Esp32Protocol.MensajeEsp32.PinBotonera -> {
                if (modoAcceso == "LOCKDOWN") {
                    esp32Manager.enviarLinea("ERR|LOCKDOWN")
                    sincronizarSoloBitacoraSeguridad("PIN rechazado en ESP32: modo LOCKDOWN activo")
                } else if (modoAcceso == "BOTONERA" || modoAcceso == "RFID_PIN") {
                    if (mensaje.pin == pinBotonera) {
                        registrarYSincronizar(
                            nuevaPuerta = true,
                            nuevoAngulo = 90,
                            nuevoBuzzer = false,
                            detalleAccion = "abrió puerta con PIN autorizado en botonera ESP32",
                            rolOverride = "ESP32 (PIN)"
                        )
                        esp32Manager.enviarLinea("OK|PUERTA=ABIERTA")
                    } else {
                        registrarYSincronizar(
                            nuevoBuzzer = true,
                            detalleAccion = "PIN incorrecto ingresado en botonera ESP32",
                            rolOverride = "SEGURIDAD"
                        )
                        esp32Manager.enviarLinea("ERR|PIN_INVALIDO")
                    }
                }
            }
            else -> Unit
        }
    }

    // Iniciar ServerSocket(5000), autoconexión entre celulares y enlace dedicado ESP32 (puerto 5002)
    LaunchedEffect(Unit) {
        wifiManager.iniciarServidor(
            obtenerEstadoParaSync = { construirEstadoParaSync(esSyncInicial = true) },
            onMensajeRecibido = { estadoRemoto, _ ->
                ultimaTramaAES = estadoRemoto.tramaCifradaAES

                // 1. Insertar en SQLite local todos los eventos del historial recibido que aún no existan
                for (eventoRemoto in estadoRemoto.historialEventos) {
                    dbHelper.insertarEventoSiNoExiste(
                        fechaHora = eventoRemoto.fechaHora,
                        usuarioRol = eventoRemoto.usuarioRol,
                        detalle = eventoRemoto.detalle
                    )
                }

                var actualizarPanelSQLite = false

                // 2. Sincronizar usuarios en SQLite (operadores + clave Supervisor) sin perder cuentas
                if (estadoRemoto.timestampOperadores > timestampOperadores) {
                    if (estadoRemoto.operadoresRegistrados.isNotEmpty()) {
                        dbHelper.sincronizarListaOperadores(estadoRemoto.operadoresRegistrados)
                    }
                    if (estadoRemoto.claveSupervisor.isNotBlank()) {
                        dbHelper.sincronizarPasswordSupervisor(estadoRemoto.claveSupervisor)
                    }
                    timestampOperadores = estadoRemoto.timestampOperadores
                    actualizarPanelSQLite = true

                    // Si el operador conectado fue eliminado por el Supervisor, cerrar su sesión al instante
                    val usuarioEnSesion = usuarioActivo
                    if (rolActivo != null && !usuarioEnSesion.isNullOrBlank() &&
                        !dbHelper.existeUsuario(usuarioEnSesion)
                    ) {
                        dbHelper.insertarEvento(
                            "SEGURIDAD",
                            "Sesión cerrada automáticamente: cuenta '$usuarioEnSesion' eliminada por el Supervisor"
                        )
                        avisoSesionRevocada = "Sesión cerrada: tu cuenta '$usuarioEnSesion' fue eliminada por el Supervisor."
                        rolActivo = null
                        usuarioActivo = null
                    }
                }

                // 2.5 Sincronizar Llave de Recuperación/Autorización de forma determinista
                val (hashLocalAuth, _, tsLocalAuth) = dbHelper.obtenerDatosSyncCodigoAuth()
                if (estadoRemoto.codigoAuthCifrado.isNotBlank() &&
                    SecurityUtils.esHashSha256(estadoRemoto.codigoAuthHash) &&
                    (estadoRemoto.timestampCodigoAuth > tsLocalAuth ||
                        (estadoRemoto.timestampCodigoAuth == tsLocalAuth && estadoRemoto.codigoAuthHash > hashLocalAuth))
                ) {
                    dbHelper.sincronizarCodigoAuth(
                        estadoRemoto.codigoAuthHash,
                        estadoRemoto.codigoAuthCifrado,
                        estadoRemoto.timestampCodigoAuth
                    )
                    codigoAutorizacionActual = dbHelper.obtenerCodigoAutorizacionVisible()
                }

                // 3. Actualizar controles si es un movimiento en vivo o si el otro dispositivo tiene un estado más reciente
                var enviarNuevoEstadoAEsp32 = false
                if (!estadoRemoto.esSincronizacionInicial || estadoRemoto.timestampModificacion >= timestampModificacion) {
                    puertaAbierta = estadoRemoto.puertaAbierta
                    anguloServo = estadoRemoto.anguloServo
                    buzzerActivo = estadoRemoto.buzzerActivo
                    modoAcceso = estadoRemoto.modoAcceso
                    uidRfid = estadoRemoto.uidRfid
                    pinBotonera = estadoRemoto.pinBotonera
                    timestampModificacion = estadoRemoto.timestampModificacion
                    actualizarPanelSQLite = true
                    enviarNuevoEstadoAEsp32 = true
                }

                if (actualizarPanelSQLite) {
                    dbHelper.guardarEstadoPanel(
                        puertaAbierta = puertaAbierta,
                        anguloServo = anguloServo,
                        buzzerActivo = buzzerActivo,
                        modoAcceso = modoAcceso,
                        uidRfid = uidRfid,
                        pinBotonera = pinBotonera,
                        timestampModificacion = timestampModificacion,
                        ultimaIp = ipDestino,
                        timestampOperadores = timestampOperadores
                    )
                }

                if (enviarNuevoEstadoAEsp32 && esp32Manager.estaConectado()) {
                    esp32Manager.enviarEstado(
                        puertaAbierta, anguloServo, buzzerActivo, modoAcceso, uidRfid, pinBotonera
                    )
                }

                // 4. Refrescar la lista de bitácora y operadores desde SQLite
                listaEventos = dbHelper.obtenerEventos()
                listaOperadores = dbHelper.obtenerOperadores()
            },
            onEstadoConexion = { nuevoEstado ->
                estadoConexion = nuevoEstado
            }
        )

        // Autodescubrimiento y autoconexión continua en Puerto 5000 (NSD + UDP + Hotspot/Emulador + Subred)
        wifiManager.iniciarAutoConexion(
            obtenerIpPreferida = { ipDestino },
            onEnlaceCambiado = { conectado, ipRemota ->
                enlaceActivo = conectado
                ipEnlazada = ipRemota
                ipLocal = wifiManager.obtenerIpLocal()
                if (conectado && !ipRemota.isNullOrBlank() && ipRemota != ipDestino) {
                    ipDestino = ipRemota
                    dbHelper.guardarEstadoPanel(
                        puertaAbierta = puertaAbierta,
                        anguloServo = anguloServo,
                        buzzerActivo = buzzerActivo,
                        modoAcceso = modoAcceso,
                        uidRfid = uidRfid,
                        pinBotonera = pinBotonera,
                        timestampModificacion = timestampModificacion,
                        ultimaIp = ipRemota,
                        timestampOperadores = timestampOperadores
                    )
                }
            }
        )

        // Enlace Wi-Fi TCP dedicado para hardware ESP32 en Puerto 5002 (paralelo al Puerto 5000)
        esp32Manager.iniciar(
            onEstadoEnlace = { conectado, endpoint, detalle ->
                esp32Conectado = conectado
                esp32Endpoint = endpoint
                estadoConexionEsp32 = detalle
                if (conectado) {
                    val ipSolo = endpoint?.substringBefore(":")?.trim()
                    if (!ipSolo.isNullOrBlank() && ipSolo != ipEsp32) {
                        ipEsp32 = ipSolo
                        dbHelper.guardarIpEsp32(ipSolo)
                    }
                    esp32Manager.enviarEstado(
                        puertaAbierta, anguloServo, buzzerActivo, modoAcceso, uidRfid, pinBotonera
                    )
                }
            },
            onMensajeHardware = { msg ->
                procesarMensajeHardwareEsp32(msg)
            },
            onTrama = { trama ->
                ultimaTramaEsp32 = trama
            }
        )
    }

    if (rolActivo == null) {
        LoginScreen(
            dbHelper = dbHelper,
            ipLocal = ipLocal,
            ipDestino = ipDestino,
            estadoConexion = estadoConexion,
            enlaceActivo = enlaceActivo,
            ipEnlazada = ipEnlazada,
            esp32Conectado = esp32Conectado,
            esp32Endpoint = esp32Endpoint,
            avisoSesionRevocada = avisoSesionRevocada,
            onLimpiarAvisoSesion = { avisoSesionRevocada = null },
            onRefrescarIpLocal = { ipLocal = wifiManager.obtenerIpLocal() },
            onIpDestinoChange = { nuevaIp ->
                ipDestino = nuevaIp
                dbHelper.guardarEstadoPanel(
                    puertaAbierta, anguloServo, buzzerActivo, modoAcceso,
                    uidRfid, pinBotonera, timestampModificacion, nuevaIp, timestampOperadores
                )
            },
            onConectarYSincronizar = {
                ipLocal = wifiManager.obtenerIpLocal()
                wifiManager.conectarYSincronizar(ipDestino) { mensajeRed, tramaAES ->
                    estadoConexion = mensajeRed
                    if (tramaAES.isNotBlank()) {
                        ultimaTramaAES = tramaAES
                    }
                }
            },
            onEventoSeguridadLogin = { detalle ->
                sincronizarSoloBitacoraSeguridad(detalle)
            },
            onDesbloquearLogin = { codigoAuth ->
                val esLlaveRecuperacion = dbHelper.esCodigoAutorizacionValido(codigoAuth)
                val esClaveSupervisor = dbHelper.esCodigoSupervisorValido(codigoAuth)
                val errorValidacion = SecurityUtils.validarDesbloqueoAcceso(
                    codigoAutorizacion = codigoAuth,
                    codigoAutorizacionValido = esLlaveRecuperacion,
                    codigoSupervisorValido = esClaveSupervisor
                )
                if (errorValidacion != null) {
                    sincronizarSoloBitacoraSeguridad("Intento fallido de desbloqueo de acceso desde Login")
                    false to errorValidacion
                } else {
                    dbHelper.reiniciarBloqueoLogin()
                    if (esLlaveRecuperacion) {
                        codigoAutorizacionActual = dbHelper.rotarCodigoAutorizacion()
                    }
                    sincronizarSoloBitacoraSeguridad(
                        detalle = "desbloqueó el acceso mediante autorización del Supervisor",
                        incluirCambioAuth = esLlaveRecuperacion
                    )
                    val msgExtra = if (esLlaveRecuperacion) {
                        " La llave de recuperación fue renovada por seguridad."
                    } else ""
                    true to "Acceso desbloqueado.$msgExtra Ya puedes iniciar sesión."
                }
            },
            onRestablecerPassword = { user, codigoAuth, nuevaPass ->
                val uLimpio = user.trim()
                if (!dbHelper.existeUsuario(uLimpio)) {
                    false to "El usuario '$uLimpio' no se encuentra registrado."
                } else {
                    val esLlaveRecuperacion = dbHelper.esCodigoAutorizacionValido(codigoAuth)
                    val esClaveSupervisor = dbHelper.esCodigoSupervisorValido(codigoAuth)
                    val errorValidacion = SecurityUtils.validarRestablecimientoPassword(
                        username = uLimpio,
                        codigoAutorizacion = codigoAuth,
                        nuevaPassword = nuevaPass,
                        codigoAutorizacionValido = esLlaveRecuperacion,
                        codigoSupervisorValido = esClaveSupervisor
                    )
                    if (errorValidacion != null) {
                        sincronizarSoloBitacoraSeguridad("Intento fallido de restablecimiento para '$uLimpio'")
                        false to errorValidacion
                    } else {
                        val rolActualizado = dbHelper.actualizarPasswordUsuario(uLimpio, nuevaPass)
                        if (rolActualizado != null) {
                            dbHelper.reiniciarBloqueoLogin()
                            if (esLlaveRecuperacion) {
                                codigoAutorizacionActual = dbHelper.rotarCodigoAutorizacion()
                            }
                            registrarYSincronizar(
                                esCambioOperadores = true,
                                rolOverride = "SEGURIDAD",
                                detalleAccion = "restableció la contraseña de '$uLimpio' ($rolActualizado)",
                                enviarAEsp32 = false
                            )
                            val msgExtra = if (esLlaveRecuperacion) {
                                " La llave de recuperación fue renovada por seguridad."
                            } else ""
                            true to "Contraseña de '$uLimpio' actualizada y acceso habilitado.$msgExtra"
                        } else {
                            false to "No se pudo actualizar la contraseña."
                        }
                    }
                }
            },
            onLoginExitoso = { rolAutenticado, usuarioAutenticado ->
                dbHelper.reiniciarBloqueoLogin()
                ipLocal = wifiManager.obtenerIpLocal()
                avisoSesionRevocada = null
                usuarioActivo = usuarioAutenticado
                rolActivo = rolAutenticado
                val actor = etiquetaActor(rolAutenticado, usuarioAutenticado)
                dbHelper.insertarEvento(actor, "inició sesión en el panel")
                listaEventos = dbHelper.obtenerEventos()
                listaOperadores = dbHelper.obtenerOperadores()
                if (wifiManager.estaEnlazado()) {
                    val estadoSync = construirEstadoParaSync(
                        esSyncInicial = true,
                        rolEmisor = actor,
                        detalle = "inició sesión en el panel"
                    )
                    wifiManager.enviarCambio(ipDestino, estadoSync) { mensajeRed, tramaAES ->
                        estadoConexion = mensajeRed
                        if (tramaAES.isNotBlank()) ultimaTramaAES = tramaAES
                    }
                }
            },
            modifier = modifier
        )
    } else {
        DashboardScreen(
            rolActivo = rolActivo!!,
            usuarioActivo = usuarioActivo,
            puertaAbierta = puertaAbierta,
            anguloServo = anguloServo,
            buzzerActivo = buzzerActivo,
            modoAcceso = modoAcceso,
            uidRfid = uidRfid,
            pinBotonera = pinBotonera,
            ipLocal = ipLocal,
            onRefrescarIpLocal = { ipLocal = wifiManager.obtenerIpLocal() },
            ipDestino = ipDestino,
            onIpDestinoChange = { nuevaIp ->
                ipDestino = nuevaIp
                dbHelper.guardarEstadoPanel(
                    puertaAbierta, anguloServo, buzzerActivo, modoAcceso,
                    uidRfid, pinBotonera, timestampModificacion, nuevaIp, timestampOperadores
                )
            },
            onConectarYSincronizar = {
                ipLocal = wifiManager.obtenerIpLocal()
                wifiManager.conectarYSincronizar(ipDestino) { mensajeRed, tramaAES ->
                    estadoConexion = mensajeRed
                    if (tramaAES.isNotBlank()) {
                        ultimaTramaAES = tramaAES
                    }
                }
            },
            estadoConexion = estadoConexion,
            enlaceActivo = enlaceActivo,
            ipEnlazada = ipEnlazada,
            esp32Conectado = esp32Conectado,
            esp32Endpoint = esp32Endpoint,
            estadoConexionEsp32 = estadoConexionEsp32,
            ultimaTramaEsp32 = ultimaTramaEsp32,
            ipEsp32 = ipEsp32,
            onIpEsp32Change = { nuevaIp ->
                ipEsp32 = nuevaIp
                dbHelper.guardarIpEsp32(nuevaIp)
            },
            onConectarEsp32 = {
                ipLocal = wifiManager.obtenerIpLocal()
                dbHelper.guardarIpEsp32(ipEsp32)
                esp32Manager.conectarManual(ipEsp32, Esp32Protocol.PUERTO_ESP32)
            },
            onDesconectarEsp32 = {
                esp32Manager.desconectar()
            },
            onPingEsp32 = {
                esp32Manager.enviarPing()
            },
            ultimaTramaAES = ultimaTramaAES,
            listaEventos = listaEventos,
            listaOperadores = listaOperadores,
            codigoAutorizacionActual = codigoAutorizacionActual,
            onRotarCodigoAutorizacion = {
                val nuevaLlave = dbHelper.rotarCodigoAutorizacion()
                codigoAutorizacionActual = nuevaLlave
                registrarYSincronizar(
                    esCambioOperadores = true,
                    detalleAccion = "rotó la llave de autorización y recuperación"
                )
                nuevaLlave
            },
            onCambiarPasswordSupervisor = { actual, nueva ->
                val error = dbHelper.cambiarPasswordSupervisor(actual, nueva)
                if (error == null) {
                    registrarYSincronizar(
                        esCambioOperadores = true,
                        detalleAccion = "actualizó la contraseña del Supervisor"
                    )
                }
                error
            },
            onRegistrarOperador = { nuevoUser, nuevaPass ->
                val uLimpio = nuevoUser.trim()
                val creado = dbHelper.registrarOperador(uLimpio, nuevaPass)
                if (creado) {
                    registrarYSincronizar(
                        esCambioOperadores = true,
                        detalleAccion = "creó la cuenta de operador '$uLimpio'"
                    )
                }
                creado
            },
            onActualizarPasswordOperador = { userExistente, nuevaPass ->
                val uLimpio = userExistente.trim()
                val actualizado = dbHelper.actualizarPasswordOperador(uLimpio, nuevaPass)
                if (actualizado) {
                    registrarYSincronizar(
                        esCambioOperadores = true,
                        detalleAccion = "actualizó la contraseña del operador '$uLimpio'"
                    )
                }
                actualizado
            },
            onEliminarOperador = { userEliminar ->
                val uLimpio = userEliminar.trim()
                val eliminado = dbHelper.eliminarOperador(uLimpio)
                if (eliminado) {
                    registrarYSincronizar(
                        esCambioOperadores = true,
                        detalleAccion = "eliminó la cuenta del operador '$uLimpio'"
                    )
                }
                eliminado
            },
            onActualizarEstado = { p, a, b, m, u, pin, det ->
                registrarYSincronizar(p, a, b, m, u, pin, false, det)
            },
            onCerrarSesion = {
                val actor = etiquetaActor(rolActivo, usuarioActivo)
                dbHelper.insertarEvento(actor, "cerró sesión")
                listaEventos = dbHelper.obtenerEventos()
                if (wifiManager.estaEnlazado()) {
                    val estadoSync = construirEstadoParaSync(
                        esSyncInicial = true,
                        rolEmisor = actor,
                        detalle = "cerró sesión"
                    )
                    wifiManager.enviarCambio(ipDestino, estadoSync) { mensajeRed, tramaAES ->
                        estadoConexion = mensajeRed
                        if (tramaAES.isNotBlank()) ultimaTramaAES = tramaAES
                    }
                }
                avisoSesionRevocada = null
                usuarioActivo = null
                rolActivo = null
            },
            modifier = modifier
        )
    }
}

@Composable
private fun BarraEstadoCard(
    activo: Boolean,
    mensaje: String,
    modifier: Modifier = Modifier
) {
    val fondo = if (activo) Color(0xFFE8F5E9) else Color(0xFFFFF8E1)
    val borde = if (activo) Color(0xFF4CAF50) else Color(0xFFFFB300)
    val colorTexto = if (activo) Color(0xFF1B5E20) else Color(0xFFE65100)

    Card(
        colors = CardDefaults.cardColors(containerColor = fondo),
        border = BorderStroke(1.5.dp, borde),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = mensaje,
            color = colorTexto,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

// =========================================================================================
// BARRA PERSISTENTE DE ESTADO DE CONEXIÓN BIDIRECCIONAL EN PUERTO 5000 Y ESP32 EN PUERTO 5002
// =========================================================================================
@Composable
fun BarraEstadoConexionIoT(
    enlaceActivo: Boolean,
    ipEnlazada: String?,
    mostrarDetalleTecnico: Boolean = true,
    modifier: Modifier = Modifier
) {
    val mensaje = when {
        enlaceActivo && mostrarDetalleTecnico && !ipEnlazada.isNullOrBlank() ->
            "🟢 Celulares enlazados • $ipEnlazada (Puerto 5000)"
        enlaceActivo -> "🟢 Sistema en línea • Sincronización activa"
        mostrarDetalleTecnico -> "🔄 Sin enlace móvil • Buscando en puerto 5000..."
        else -> "🔄 Conectando con el sistema de acceso..."
    }
    BarraEstadoCard(activo = enlaceActivo, mensaje = mensaje, modifier = modifier)
}

@Composable
fun BarraEstadoConexionEsp32(
    esp32Conectado: Boolean,
    esp32Endpoint: String?,
    modifier: Modifier = Modifier
) {
    val mensaje = if (esp32Conectado) {
        "🟢 ESP32 conectado • ${esp32Endpoint ?: "Puerto ${Esp32Protocol.PUERTO_ESP32}"}"
    } else {
        "🔌 ESP32 sin enlace • Puerto ${Esp32Protocol.PUERTO_ESP32} en espera"
    }
    BarraEstadoCard(activo = esp32Conectado, mensaje = mensaje, modifier = modifier)
}

// =========================================================================================
// PANTALLA 1: LOGIN CON VALIDACIÓN ISO/IEC 27400 Y BLOQUEO DEFINITIVO TRAS 3 INTENTOS FALLIDOS
// =========================================================================================
@Composable
fun LoginScreen(
    dbHelper: DatabaseHelper,
    ipLocal: String,
    ipDestino: String,
    estadoConexion: String,
    enlaceActivo: Boolean,
    ipEnlazada: String?,
    esp32Conectado: Boolean,
    esp32Endpoint: String?,
    avisoSesionRevocada: String?,
    onLimpiarAvisoSesion: () -> Unit,
    onRefrescarIpLocal: () -> Unit,
    onIpDestinoChange: (String) -> Unit,
    onConectarYSincronizar: () -> Unit,
    onEventoSeguridadLogin: (detalle: String) -> Unit,
    onDesbloquearLogin: (codigoAutorizacion: String) -> Pair<Boolean, String>,
    onRestablecerPassword: (username: String, codigoAutorizacion: String, nuevaPassword: String) -> Pair<Boolean, String>,
    onLoginExitoso: (rol: String, username: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val (intentosIniciales, bloqueadoInicial) = remember { dbHelper.obtenerEstadoBloqueoLogin() }
    var usuario by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var intentosFallidos by rememberSaveable { mutableIntStateOf(intentosIniciales) }
    var loginBloqueado by rememberSaveable { mutableStateOf(bloqueadoInicial) }
    var mensajeError by rememberSaveable {
        mutableStateOf(
            if (bloqueadoInicial) {
                "Acceso bloqueado tras ${SecurityUtils.MAX_INTENTOS_LOGIN} intentos fallidos. Requiere autorización del Supervisor para desbloquear."
            } else null
        )
    }
    var esAlertaIso by rememberSaveable { mutableStateOf(bloqueadoInicial) }

    // Estados para desbloqueo / restablecimiento de contraseña y respaldo manual técnico
    var mostrarRecuperacion by rememberSaveable { mutableStateOf(false) }
    var mostrarRespaldoRed by rememberSaveable { mutableStateOf(false) }
    var usuarioRecuperar by rememberSaveable { mutableStateOf("") }
    var codigoAutorizacion by rememberSaveable { mutableStateOf("") }
    var nuevaPasswordRecuperar by rememberSaveable { mutableStateOf("") }
    var mensajeRecuperacion by rememberSaveable { mutableStateOf<String?>(null) }
    var recuperacionOk by rememberSaveable { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val ocultarTeclado = {
        focusManager.clearFocus()
        keyboardController?.hide()
        Unit
    }

    LaunchedEffect(mostrarRespaldoRed) {
        if (mostrarRespaldoRed) {
            delay(80)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BarraEstadoConexionIoT(
            enlaceActivo = enlaceActivo,
            ipEnlazada = ipEnlazada,
            mostrarDetalleTecnico = false
        )
        Spacer(modifier = Modifier.height(6.dp))
        BarraEstadoConexionEsp32(
            esp32Conectado = esp32Conectado,
            esp32Endpoint = esp32Endpoint
        )
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "AccessGuard IoT",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Control de Acceso y Seguridad Operacional",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFFEFF6FF), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "🔒 Acceso Corporativo Protegido",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E3A5F)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!mostrarRecuperacion) {
                    if (avisoSesionRevocada != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                            border = BorderStroke(1.dp, Color(0xFFD32F2F)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🔒 $avisoSesionRevocada",
                                color = Color(0xFFB71C1C),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    OutlinedTextField(
                        value = usuario,
                        onValueChange = {
                            usuario = it
                            if (avisoSesionRevocada != null) onLimpiarAvisoSesion()
                        },
                        label = { Text("Usuario") },
                        singleLine = true,
                        enabled = !loginBloqueado,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(rememberScrollAlEnfocarModifier())
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Contraseña") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !loginBloqueado,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { ocultarTeclado() }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(rememberScrollAlEnfocarModifier())
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Alerta Roja de Seguridad ISO/IEC 27400 o Error de Credenciales
                    if (mensajeError != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (esAlertaIso) Color(0xFFFFEBEE) else Color(0xFFFFF3E0)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = mensajeError!!,
                                color = if (esAlertaIso) Color(0xFFB71C1C) else Color(0xFFE65100),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    fun registrarFalloEnPantalla(
                        mensajeParcial: String,
                        eventoSeguridad: String?,
                        alertaRoja: Boolean
                    ) {
                        val (intentos, bloqueadoAhora) = dbHelper.registrarFalloLogin()
                        intentosFallidos = intentos
                        loginBloqueado = bloqueadoAhora
                        if (eventoSeguridad != null) {
                            onEventoSeguridadLogin(eventoSeguridad)
                        }
                        if (bloqueadoAhora) {
                            onEventoSeguridadLogin(
                                "Acceso bloqueado definitivamente tras ${SecurityUtils.MAX_INTENTOS_LOGIN} intentos fallidos (requiere autorización del Supervisor)"
                            )
                            esAlertaIso = true
                            mensajeError = "Acceso bloqueado tras ${SecurityUtils.MAX_INTENTOS_LOGIN} intentos fallidos. Requiere autorización del Supervisor para desbloquear."
                        } else {
                            esAlertaIso = alertaRoja
                            mensajeError = "$mensajeParcial Intento $intentos de ${SecurityUtils.MAX_INTENTOS_LOGIN}."
                        }
                    }

                    Button(
                        onClick = {
                            ocultarTeclado()
                            if (loginBloqueado) return@Button

                            // 1. Validar Norma ISO/IEC 27400 (Prohibición de credenciales por defecto)
                            val credencialCombinada = "${usuario.trim()}/${password.trim()}"
                            if (SecurityUtils.esCredencialProhibida(usuario) ||
                                SecurityUtils.esCredencialProhibida(password) ||
                                SecurityUtils.esCredencialProhibida(credencialCombinada)
                            ) {
                                val userLog = usuario.trim().ifBlank { "sin usuario" }
                                registrarFalloEnPantalla(
                                    mensajeParcial = "Acceso denegado: credencial restringida por política de seguridad.",
                                    eventoSeguridad = "Intento bloqueado por uso de credencial restringida ($userLog)",
                                    alertaRoja = true
                                )
                                return@Button
                            }

                            // 2. Validar contra la tabla usuarios en SQLite obteniendo rol y nombre exacto
                            val auth = dbHelper.validarUsuarioConNombre(usuario, password)
                            if (auth != null) {
                                intentosFallidos = 0
                                loginBloqueado = false
                                mensajeError = null
                                onLimpiarAvisoSesion()
                                onLoginExitoso(auth.first, auth.second)
                            } else {
                                registrarFalloEnPantalla(
                                    mensajeParcial = "Usuario o contraseña incorrectos.",
                                    eventoSeguridad = null,
                                    alertaRoja = false
                                )
                            }
                        },
                        enabled = !loginBloqueado,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (loginBloqueado) "Acceso Bloqueado (Máx. ${SecurityUtils.MAX_INTENTOS_LOGIN} intentos)"
                            else "Iniciar Sesión"
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    TextButton(
                        onClick = {
                            ocultarTeclado()
                            mostrarRecuperacion = true
                            if (usuarioRecuperar.isBlank() && usuario.isNotBlank()) {
                                usuarioRecuperar = usuario.trim()
                            }
                            mensajeRecuperacion = null
                        }
                    ) {
                        Text(
                            text = if (loginBloqueado) "🔒 Desbloquear Acceso / Restablecer Contraseña"
                            else "¿Olvidaste tu contraseña? / Restablecer Contraseña",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (loginBloqueado) Color(0xFFB71C1C) else Color.Unspecified
                        )
                    }
                } else {
                    Text(
                        text = if (loginBloqueado) "Desbloquear Acceso / Restablecer Contraseña"
                        else "Restablecer Contraseña",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = usuarioRecuperar,
                        onValueChange = { usuarioRecuperar = it },
                        label = {
                            Text(
                                if (loginBloqueado) "Usuario (opcional si solo desbloqueas)"
                                else "Usuario"
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(rememberScrollAlEnfocarModifier())
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = codigoAutorizacion,
                        onValueChange = { codigoAutorizacion = it },
                        label = { Text("Código de Autorización") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(rememberScrollAlEnfocarModifier())
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = nuevaPasswordRecuperar,
                        onValueChange = { nuevaPasswordRecuperar = it },
                        label = {
                            Text(
                                if (loginBloqueado) "Nueva Contraseña (si deseas cambiarla)"
                                else "Nueva Contraseña"
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { ocultarTeclado() }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(rememberScrollAlEnfocarModifier())
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (loginBloqueado) {
                            "Ingresa la llave de recuperación (AG-XXXX-XXXX) o clave del Supervisor. Pulsa 'Solo Desbloquear Acceso' para habilitar el Login, o completa los 3 campos para cambiar la contraseña."
                        } else {
                            "Usa la llave de recuperación entregada por el Supervisor (o su contraseña vigente). Nueva clave: 8+ caracteres, mayúscula, minúscula y número."
                        },
                        fontSize = 11.sp,
                        color = Color.Gray
                    )

                    if (mensajeRecuperacion != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (recuperacionOk) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = mensajeRecuperacion!!,
                                color = if (recuperacionOk) Color(0xFF2E7D32) else Color(0xFFB71C1C),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            ocultarTeclado()
                            val (exito, mensaje) = onRestablecerPassword(
                                usuarioRecuperar,
                                codigoAutorizacion,
                                nuevaPasswordRecuperar
                            )
                            recuperacionOk = exito
                            mensajeRecuperacion = mensaje
                            if (exito) {
                                usuario = usuarioRecuperar.trim()
                                password = ""
                                codigoAutorizacion = ""
                                nuevaPasswordRecuperar = ""
                                mensajeError = null
                                intentosFallidos = 0
                                loginBloqueado = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (loginBloqueado) "Actualizar Contraseña y Desbloquear"
                            else "Actualizar Contraseña"
                        )
                    }

                    if (loginBloqueado) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                ocultarTeclado()
                                val (exito, mensaje) = onDesbloquearLogin(codigoAutorizacion)
                                recuperacionOk = exito
                                mensajeRecuperacion = mensaje
                                if (exito) {
                                    password = ""
                                    codigoAutorizacion = ""
                                    mensajeError = null
                                    intentosFallidos = 0
                                    loginBloqueado = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Solo Desbloquear Acceso")
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    TextButton(
                        onClick = {
                            ocultarTeclado()
                            mostrarRecuperacion = false
                            mensajeRecuperacion = null
                        }
                    ) {
                        Text(
                            text = "← Volver a Iniciar Sesión",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        TextButton(
            onClick = { mostrarRespaldoRed = !mostrarRespaldoRed }
        ) {
            Text(
                text = if (mostrarRespaldoRed) "Ocultar configuración de enlace"
                else "Configuración avanzada de enlace",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }

        // Tarjeta colapsable de respaldo manual (oculta por defecto y sin tecnicismos innecesarios)
        if (mostrarRespaldoRed) {
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7FA)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enlace Manual con Servidor",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Terminal local: $ipLocal",
                                fontSize = 11.sp,
                                color = Color(0xFF475569),
                                fontWeight = FontWeight.Medium
                            )
                        }
                        TextButton(onClick = onRefrescarIpLocal) {
                            Text("Actualizar", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = ipDestino,
                            onValueChange = onIpDestinoChange,
                            label = { Text("IP del servidor / Supervisor") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { ocultarTeclado() }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .then(rememberScrollAlEnfocarModifier())
                        )
                        Button(onClick = {
                            ocultarTeclado()
                            onConectarYSincronizar()
                        }) {
                            Text("Conectar", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Estado: $estadoConexion • Remoto: ${ipEnlazada ?: "En espera"}",
                        fontSize = 11.sp,
                        color = Color(0xFF475569)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

// =========================================================================================
// PANTALLA 2: PANEL PRINCIPAL SEGÚN ROL (SUPERVISOR VS. OPERADOR)
// =========================================================================================
@Composable
fun DashboardScreen(
    rolActivo: String,
    usuarioActivo: String?,
    puertaAbierta: Boolean,
    anguloServo: Int,
    buzzerActivo: Boolean,
    modoAcceso: String,
    uidRfid: String,
    pinBotonera: String,
    ipLocal: String,
    onRefrescarIpLocal: () -> Unit,
    ipDestino: String,
    onIpDestinoChange: (String) -> Unit,
    onConectarYSincronizar: () -> Unit,
    estadoConexion: String,
    enlaceActivo: Boolean,
    ipEnlazada: String?,
    esp32Conectado: Boolean,
    esp32Endpoint: String?,
    estadoConexionEsp32: String,
    ultimaTramaEsp32: String,
    ipEsp32: String,
    onIpEsp32Change: (String) -> Unit,
    onConectarEsp32: () -> Unit,
    onDesconectarEsp32: () -> Unit,
    onPingEsp32: () -> Unit,
    ultimaTramaAES: String,
    listaEventos: List<EventoBitacora>,
    listaOperadores: List<UsuarioOperador>,
    codigoAutorizacionActual: String,
    onRotarCodigoAutorizacion: () -> String,
    onCambiarPasswordSupervisor: (passwordActual: String, nuevaPassword: String) -> String?,
    onRegistrarOperador: (username: String, password: String) -> Boolean,
    onActualizarPasswordOperador: (username: String, nuevaPassword: String) -> Boolean,
    onEliminarOperador: (username: String) -> Boolean,
    onActualizarEstado: (
        puerta: Boolean,
        angulo: Int,
        buzzer: Boolean,
        modo: String,
        uid: String,
        pin: String,
        detalle: String
    ) -> Unit,
    onCerrarSesion: () -> Unit,
    modifier: Modifier = Modifier
) {
    val esSupervisor = (rolActivo == "SUPERVISOR")
    val enLockdown = (modoAcceso == "LOCKDOWN")
    // El Operador puede operar la puerta salvo que el Supervisor haya activado el modo LOCKDOWN
    val controlesPuertaHabilitados = esSupervisor || !enLockdown

    // Estados locales para el Slider, edición de claves y CRUD de Operadores del Supervisor
    var sliderAngulo by remember(anguloServo) { mutableFloatStateOf(anguloServo.toFloat()) }
    var campoUid by remember(uidRfid) { mutableStateOf(uidRfid) }
    var campoPin by remember(pinBotonera) { mutableStateOf(pinBotonera) }
    var mensajeClaveIso by remember { mutableStateOf<String?>(null) }
    var claveActualizadaOk by remember { mutableStateOf(false) }

    var nuevoUsuarioOp by remember { mutableStateOf("") }
    var nuevaPassOp by remember { mutableStateOf("") }
    var usuarioOpEditando by remember { mutableStateOf<String?>(null) }
    var mensajeRegistroOp by remember { mutableStateOf<String?>(null) }
    var registroOpOk by remember { mutableStateOf(false) }
    var mostrarConfigRed by rememberSaveable { mutableStateOf(false) }
    var mostrarConfigEsp32 by rememberSaveable { mutableStateOf(false) }

    // Estados de seguridad del Supervisor (Llave de recuperación y rotación de clave propia)
    var mostrarLlaveRecuperacion by remember { mutableStateOf(false) }
    var mensajeLlaveRotada by remember { mutableStateOf<String?>(null) }
    var mostrarCambioPassSup by remember { mutableStateOf(false) }
    var passSupActual by remember { mutableStateOf("") }
    var passSupNueva by remember { mutableStateOf("") }
    var passSupConfirmar by remember { mutableStateOf("") }
    var mensajePassSup by remember { mutableStateOf<String?>(null) }
    var passSupOk by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val ocultarTeclado = {
        focusManager.clearFocus()
        keyboardController?.hide()
        Unit
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // -------------------------------------------------------------------------
        // 0. INDICADORES VISUALES DE ENLACE: CELULARES (PUERTO 5000) Y ESP32 (PUERTO 5002)
        // -------------------------------------------------------------------------
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BarraEstadoConexionIoT(
                enlaceActivo = enlaceActivo,
                ipEnlazada = ipEnlazada,
                mostrarDetalleTecnico = true
            )
            BarraEstadoConexionEsp32(
                esp32Conectado = esp32Conectado,
                esp32Endpoint = esp32Endpoint
            )
        }

        // -------------------------------------------------------------------------
        // 1. ENCABEZADO DE SESIÓN ACTIVA + CERRAR SESIÓN
        // -------------------------------------------------------------------------
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (esSupervisor) Color(0xFF1E3A5F) else Color(0xFF0F766E)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (esSupervisor) "Panel de Supervisión"
                        else "Panel de Operación",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Sesión: ${usuarioActivo ?: rolActivo} • ${
                            if (esSupervisor) "Control Total" else "Operación Autorizada"
                        }",
                        color = Color(0xFFE0F2FE),
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = onCerrarSesion,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF1E3A5F)
                    )
                ) {
                    Text("Cerrar Sesión", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // -------------------------------------------------------------------------
        // 2. SINCRONIZACIÓN DEL SISTEMA Y ENLACE MANUAL COLAPSABLE
        // -------------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sincronización del Sistema",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (enlaceActivo) "Enlazado y sincronizado en tiempo real"
                            else "En espera de enlace con otro terminal",
                            fontSize = 12.sp,
                            color = Color(0xFF475569)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = onConectarYSincronizar) {
                            Text("Sincronizar", fontSize = 11.sp)
                        }
                        TextButton(onClick = { mostrarConfigRed = !mostrarConfigRed }) {
                            Text(
                                text = if (mostrarConfigRed) "Ocultar" else "Avanzado",
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                if (mostrarConfigRed) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Dirección de este equipo: $ipLocal",
                            fontSize = 11.sp,
                            color = Color(0xFF475569),
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(onClick = onRefrescarIpLocal) {
                            Text("Actualizar", fontSize = 11.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = ipDestino,
                            onValueChange = onIpDestinoChange,
                            label = { Text("IP del dispositivo destino") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { ocultarTeclado() }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .then(rememberScrollAlEnfocarModifier())
                        )
                        Button(onClick = {
                            ocultarTeclado()
                            onConectarYSincronizar()
                        }) {
                            Text("Conectar", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Estado: $estadoConexion • Remoto: ${ipEnlazada ?: "En espera"}",
                        fontSize = 11.sp,
                        color = Color(0xFF475569)
                    )
                }
            }
        }

        // -------------------------------------------------------------------------
        // 2.5. CONEXIÓN HARDWARE ESP32 (PUERTO 5002 EN PARALELO AL PUERTO 5000)
        // -------------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Conexión Hardware ESP32 (Puerto ${Esp32Protocol.PUERTO_ESP32})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (esp32Conectado) {
                                "Enlazado con hardware en ${esp32Endpoint ?: ipEsp32}"
                            } else {
                                "Sin enlace • Escuchando en puerto ${Esp32Protocol.PUERTO_ESP32}"
                            },
                            fontSize = 12.sp,
                            color = if (esp32Conectado) Color(0xFF1B5E20) else Color(0xFF475569)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (esp32Conectado) onDesconectarEsp32() else onConectarEsp32()
                            }
                        ) {
                            Text(if (esp32Conectado) "Desconectar" else "Conectar", fontSize = 11.sp)
                        }
                        TextButton(onClick = { mostrarConfigEsp32 = !mostrarConfigEsp32 }) {
                            Text(
                                text = if (mostrarConfigEsp32) "Ocultar" else "Configurar",
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                if (mostrarConfigEsp32) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "IP para ESP32: $ipLocal:${Esp32Protocol.PUERTO_ESP32}",
                            fontSize = 11.sp,
                            color = Color(0xFF475569),
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(onClick = onRefrescarIpLocal) {
                            Text("Actualizar", fontSize = 11.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = ipEsp32,
                            onValueChange = onIpEsp32Change,
                            label = { Text("IP del ESP32 (puerto ${Esp32Protocol.PUERTO_ESP32})") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { ocultarTeclado() }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .then(rememberScrollAlEnfocarModifier())
                        )
                        Button(onClick = {
                            ocultarTeclado()
                            onConectarEsp32()
                        }) {
                            Text("Conectar", fontSize = 11.sp)
                        }
                    }

                    if (esp32Conectado) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = onPingEsp32,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Enviar Ping de Prueba al ESP32", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Estado: $estadoConexionEsp32",
                        fontSize = 11.sp,
                        color = Color(0xFF475569)
                    )
                    Text(
                        text = "Última trama: $ultimaTramaEsp32",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }
        }

        // -------------------------------------------------------------------------
        // 3. TARJETA DE MONITOREO EN VIVO - SALA DE SERVIDORES
        // -------------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Monitoreo en Vivo — Sala de Servidores",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Estado de Puerta:", fontWeight = FontWeight.Medium)
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (puertaAbierta) Color(0xFF2E7D32) else Color(0xFFC62828),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (puertaAbierta) "ABIERTA ($anguloServo°)" else "CERRADA ($anguloServo°)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• Alarma Sonora: ${if (buzzerActivo) "ACTIVA" else "DESACTIVADA"}",
                    fontSize = 13.sp
                )
                Text(
                    text = "• Modo de Acceso: ${
                        when (modoAcceso) {
                            "RFID" -> "Tarjeta RFID"
                            "BOTONERA" -> "Código PIN"
                            "RFID_PIN" -> "Doble Factor (RFID + PIN)"
                            else -> "Bloqueo de Emergencia (Lockdown)"
                        }
                    }",
                    fontWeight = FontWeight.SemiBold,
                    color = if (enLockdown) Color(0xFFC62828) else Color.Unspecified,
                    fontSize = 13.sp
                )
                val resumenCredenciales = if (esSupervisor) {
                    "Tarjeta $uidRfid • PIN $pinBotonera"
                } else {
                    "Tarjeta ••••-${uidRfid.takeLast(2)} • PIN •••• (Restringido)"
                }
                Text(
                    text = "• Credenciales Activas: $resumenCredenciales",
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "• Canal de comunicación: ${if (enlaceActivo) "Enlace cifrado verificado" else "Operación local protegida"}",
                    fontSize = 12.sp,
                    color = Color(0xFF475569)
                )
            }
        }

        // -------------------------------------------------------------------------
        // 4. CONTROLES OPERATIVOS DE PUERTA (SUPERVISOR Y OPERADOR, SALVO LOCKDOWN)
        // -------------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Control de Acceso y Puerta",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                // Aviso de bloqueo cuando el Supervisor activó Lockdown y el usuario es Operador
                if (!controlesPuertaHabilitados) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "🔒 Operación restringida por el Supervisor (Bloqueo de Emergencia activo).",
                            color = Color(0xFFB71C1C),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Switch: Apertura / Cierre de puerta
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Apertura / Cierre de Puerta")
                    Switch(
                        checked = puertaAbierta,
                        enabled = controlesPuertaHabilitados,
                        onCheckedChange = { abrir ->
                            val nuevoAngulo = if (abrir) 90 else 0
                            val accion = if (abrir) "abrió la puerta (90°)" else "cerró la puerta (0°)"
                            onActualizarEstado(
                                abrir,
                                nuevoAngulo,
                                buzzerActivo,
                                modoAcceso,
                                uidRfid,
                                pinBotonera,
                                accion
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Slider (0° a 180°): Ajustar ángulo de apertura
                Text(text = "Ángulo de Apertura: ${sliderAngulo.roundToInt()}° (0° a 180°)")
                Slider(
                    value = sliderAngulo,
                    onValueChange = { sliderAngulo = it },
                    onValueChangeFinished = {
                        val anguloFinal = sliderAngulo.roundToInt()
                        val abierta = anguloFinal > 0
                        onActualizarEstado(
                            abierta,
                            anguloFinal,
                            buzzerActivo,
                            modoAcceso,
                            uidRfid,
                            pinBotonera,
                            "ajustó el ángulo de apertura a $anguloFinal°"
                        )
                    },
                    valueRange = 0f..180f,
                    enabled = controlesPuertaHabilitados
                )

                // Checkbox: Activar / Silenciar alarma sonora
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = buzzerActivo,
                        enabled = controlesPuertaHabilitados,
                        onCheckedChange = { activo ->
                            val accion = if (activo) "activó la alarma sonora" else "silenció la alarma sonora"
                            onActualizarEstado(
                                puertaAbierta,
                                anguloServo,
                                activo,
                                modoAcceso,
                                uidRfid,
                                pinBotonera,
                                accion
                            )
                        }
                    )
                    Text(text = "Alarma sonora de emergencia")
                }
            }
        }

        // -------------------------------------------------------------------------
        // 5. CONFIGURACIÓN ADMINISTRATIVA (SUPERVISOR) O POLÍTICA RESUMIDA (OPERADOR)
        // -------------------------------------------------------------------------
        if (esSupervisor) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Configuración de Seguridad y Accesos",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFF1E3A5F)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(text = "Modo de Validación en Puerta:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                    // RadioButton 1: Solo Tarjeta RFID
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = (modoAcceso == "RFID"),
                            onClick = {
                                onActualizarEstado(
                                    puertaAbierta,
                                    anguloServo,
                                    buzzerActivo,
                                    "RFID",
                                    uidRfid,
                                    pinBotonera,
                                    "cambió el modo de acceso a Solo Tarjeta RFID"
                                )
                            }
                        )
                        Text(text = "Solo Tarjeta RFID", fontSize = 13.sp)
                    }

                    // RadioButton 2: Solo Código PIN
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = (modoAcceso == "BOTONERA"),
                            onClick = {
                                onActualizarEstado(
                                    puertaAbierta,
                                    anguloServo,
                                    buzzerActivo,
                                    "BOTONERA",
                                    uidRfid,
                                    pinBotonera,
                                    "cambió el modo de acceso a Solo Código PIN"
                                )
                            }
                        )
                        Text(text = "Solo Código PIN", fontSize = 13.sp)
                    }

                    // RadioButton 3: Doble Factor (RFID + PIN)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = (modoAcceso == "RFID_PIN"),
                            onClick = {
                                onActualizarEstado(
                                    puertaAbierta,
                                    anguloServo,
                                    buzzerActivo,
                                    "RFID_PIN",
                                    uidRfid,
                                    pinBotonera,
                                    "activó Doble Factor (RFID + PIN)"
                                )
                            }
                        )
                        Text(
                            text = "Doble Factor (Tarjeta RFID + Código PIN)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1E3A5F)
                        )
                    }

                    // RadioButton 4: Bloqueo de Emergencia (Lockdown)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = (modoAcceso == "LOCKDOWN"),
                            onClick = {
                                onActualizarEstado(
                                    false,
                                    0,
                                    buzzerActivo,
                                    "LOCKDOWN",
                                    uidRfid,
                                    pinBotonera,
                                    "activó Bloqueo de Emergencia (Puerta cerrada a 0°)"
                                )
                            }
                        )
                        Text(
                            text = "Bloqueo de Emergencia (Cierra puerta e inhibe operación)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFC62828)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Credenciales Físicas de Acceso:",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = campoUid,
                            onValueChange = { campoUid = it },
                            label = { Text("UID Tarjeta RFID") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Next) }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .then(rememberScrollAlEnfocarModifier())
                        )
                        OutlinedTextField(
                            value = campoPin,
                            onValueChange = { campoPin = it },
                            label = { Text("PIN de Teclado") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { ocultarTeclado() }
                            ),
                            modifier = Modifier
                                .width(140.dp)
                                .then(rememberScrollAlEnfocarModifier())
                        )
                    }

                    if (mensajeClaveIso != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = mensajeClaveIso!!,
                            color = if (claveActualizadaOk) Color(0xFF2E7D32) else Color(0xFFB71C1C),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            ocultarTeclado()
                            val errorValidacion = SecurityUtils.validarCredencialesAcceso(campoUid, campoPin)
                            if (errorValidacion != null) {
                                claveActualizadaOk = false
                                mensajeClaveIso = errorValidacion
                            } else {
                                claveActualizadaOk = true
                                mensajeClaveIso = "Credenciales actualizadas y sincronizadas."
                                val uidLimpio = campoUid.trim()
                                onActualizarEstado(
                                    puertaAbierta,
                                    anguloServo,
                                    buzzerActivo,
                                    modoAcceso,
                                    uidLimpio,
                                    campoPin.trim(),
                                    "rotó credenciales físicas (Tarjeta ****-${uidLimpio.takeLast(2)}, PIN ****)"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Actualizar Credenciales")
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // -----------------------------------------------------------------
                    // GESTIÓN DE CUENTAS DE OPERADORES (EXCLUSIVA DEL SUPERVISOR)
                    // -----------------------------------------------------------------
                    Text(
                        text = "Gestión de Operadores:",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for (op in listaOperadores) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7FA)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "👤 ${op.username}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "Operador autorizado",
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                usuarioOpEditando = op.username
                                                nuevoUsuarioOp = op.username
                                                nuevaPassOp = ""
                                                mensajeRegistroOp = "Editando clave de '${op.username}'. Ingresa la nueva contraseña abajo."
                                                registroOpOk = true
                                            }
                                        ) {
                                            Text("Editar Clave", fontSize = 11.sp)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                if (listaOperadores.size <= 1) {
                                                    registroOpOk = false
                                                    mensajeRegistroOp = "Debe mantenerse al menos una cuenta de Operador activa."
                                                } else {
                                                    val borrado = onEliminarOperador(op.username)
                                                    if (borrado) {
                                                        if (usuarioOpEditando == op.username) {
                                                            usuarioOpEditando = null
                                                            nuevoUsuarioOp = ""
                                                            nuevaPassOp = ""
                                                        }
                                                        registroOpOk = true
                                                        mensajeRegistroOp = "Operador '${op.username}' eliminado y sincronizado."
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = Color(0xFFC62828)
                                            )
                                        ) {
                                            Text("Eliminar", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (usuarioOpEditando != null)
                                "Editar contraseña de '$usuarioOpEditando'"
                            else
                                "Agregar nuevo operador:",
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = if (usuarioOpEditando != null) Color(0xFF1E3A5F) else Color.Unspecified
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = nuevoUsuarioOp,
                                onValueChange = { nuevoUsuarioOp = it },
                                label = { Text("Usuario") },
                                enabled = (usuarioOpEditando == null),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Next) }
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .then(rememberScrollAlEnfocarModifier())
                            )
                            OutlinedTextField(
                                value = nuevaPassOp,
                                onValueChange = { nuevaPassOp = it },
                                label = {
                                    Text(if (usuarioOpEditando != null) "Nueva Contraseña" else "Contraseña")
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { ocultarTeclado() }
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .then(rememberScrollAlEnfocarModifier())
                            )
                        }

                        if (mensajeRegistroOp != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = mensajeRegistroOp!!,
                                color = if (registroOpOk) Color(0xFF2E7D32) else Color(0xFFB71C1C),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    ocultarTeclado()
                                    val uLimpio = (usuarioOpEditando ?: nuevoUsuarioOp).trim()
                                    val pLimpia = nuevaPassOp.trim()
                                    val errorUsuario = SecurityUtils.validarNuevoOperador(uLimpio, pLimpia)

                                    if (errorUsuario != null) {
                                        registroOpOk = false
                                        mensajeRegistroOp = errorUsuario
                                    } else if (usuarioOpEditando != null) {
                                        val actualizado = onActualizarPasswordOperador(uLimpio, pLimpia)
                                        if (actualizado) {
                                            registroOpOk = true
                                            mensajeRegistroOp = "Contraseña del operador '$uLimpio' actualizada y sincronizada."
                                            usuarioOpEditando = null
                                            nuevoUsuarioOp = ""
                                            nuevaPassOp = ""
                                        } else {
                                            registroOpOk = false
                                            mensajeRegistroOp = "No se pudo actualizar la contraseña de '$uLimpio'."
                                        }
                                    } else {
                                        val exito = onRegistrarOperador(uLimpio, pLimpia)
                                        if (exito) {
                                            registroOpOk = true
                                            mensajeRegistroOp = "Operador '$uLimpio' creado y sincronizado."
                                            nuevoUsuarioOp = ""
                                            nuevaPassOp = ""
                                        } else {
                                            registroOpOk = false
                                            mensajeRegistroOp = "El usuario '$uLimpio' ya existe. Usa 'Editar Clave' para actualizar su contraseña."
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    if (usuarioOpEditando != null) "Guardar Nueva Clave"
                                    else "Crear Operador"
                                )
                            }

                            if (usuarioOpEditando != null) {
                                OutlinedButton(
                                    onClick = {
                                        usuarioOpEditando = null
                                        nuevoUsuarioOp = ""
                                        nuevaPassOp = ""
                                        mensajeRegistroOp = null
                                    }
                                ) {
                                    Text("Cancelar")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))

                        // -----------------------------------------------------------------
                        // SEGURIDAD DE SUPERVISIÓN: LLAVE DE RECUPERACIÓN Y CLAVE PROPIA
                        // -----------------------------------------------------------------
                        Text(
                            text = "Seguridad de Supervisión y Recuperación:",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7FA)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Llave de Recuperación y Autorización",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = if (mostrarLlaveRecuperacion) codigoAutorizacionActual else "••••••••••••",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = Color(0xFF1E3A5F)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = { mostrarLlaveRecuperacion = !mostrarLlaveRecuperacion }
                                        ) {
                                            Text(
                                                text = if (mostrarLlaveRecuperacion) "Ocultar" else "Ver Llave",
                                                fontSize = 11.sp
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                onRotarCodigoAutorizacion()
                                                mostrarLlaveRecuperacion = true
                                                mensajeLlaveRotada = "Nueva llave generada y sincronizada."
                                            }
                                        ) {
                                            Text("Rotar", fontSize = 11.sp)
                                        }
                                    }
                                }
                                if (mostrarLlaveRecuperacion) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Guarda esta llave fuera del dispositivo para recuperar acceso si olvidas tu contraseña, o úsala para autorizar un restablecimiento. Se renueva al usarse.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF475569)
                                    )
                                }
                                if (mensajeLlaveRotada != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = mensajeLlaveRotada!!,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        TextButton(
                            onClick = {
                                mostrarCambioPassSup = !mostrarCambioPassSup
                                if (!mostrarCambioPassSup) {
                                    mensajePassSup = null
                                }
                            }
                        ) {
                            Text(
                                text = if (mostrarCambioPassSup) "Ocultar cambio de contraseña del Supervisor"
                                else "Cambiar mi contraseña de Supervisor",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (mostrarCambioPassSup) {
                            OutlinedTextField(
                                value = passSupActual,
                                onValueChange = { passSupActual = it },
                                label = { Text("Contraseña actual") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Next) }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(rememberScrollAlEnfocarModifier())
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = passSupNueva,
                                    onValueChange = { passSupNueva = it },
                                    label = { Text("Nueva contraseña") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Next
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onNext = { focusManager.moveFocus(FocusDirection.Next) }
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(rememberScrollAlEnfocarModifier())
                                )
                                OutlinedTextField(
                                    value = passSupConfirmar,
                                    onValueChange = { passSupConfirmar = it },
                                    label = { Text("Confirmar") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { ocultarTeclado() }
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(rememberScrollAlEnfocarModifier())
                                )
                            }
                            if (mensajePassSup != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = mensajePassSup!!,
                                    color = if (passSupOk) Color(0xFF2E7D32) else Color(0xFFB71C1C),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    ocultarTeclado()
                                    if (passSupNueva.trim() != passSupConfirmar.trim()) {
                                        passSupOk = false
                                        mensajePassSup = "Las contraseñas nuevas no coinciden."
                                    } else {
                                        val error = onCambiarPasswordSupervisor(passSupActual, passSupNueva)
                                        if (error == null) {
                                            passSupOk = true
                                            mensajePassSup = "Contraseña del Supervisor actualizada y sincronizada."
                                            passSupActual = ""
                                            passSupNueva = ""
                                            passSupConfirmar = ""
                                        } else {
                                            passSupOk = false
                                            mensajePassSup = error
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Actualizar Contraseña de Supervisor")
                            }
                        }
                    }
                }
            }
        } else {
            // Vista resumida de políticas en modo Operador (solo lectura, sin módulo de cuentas)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Política de Seguridad Vigente",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFF0F766E)
                    )
                    Text(
                        text = "Configuración establecida por Supervisión (solo lectura)",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Validación en puerta: ${
                            when (modoAcceso) {
                                "RFID" -> "Solo Tarjeta RFID"
                                "BOTONERA" -> "Solo Código PIN"
                                "RFID_PIN" -> "Doble Factor (Tarjeta RFID + Código PIN)"
                                else -> "Bloqueo de Emergencia activo (operación suspendida)"
                            }
                        }",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (enLockdown) Color(0xFFC62828) else Color(0xFF1E293B)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Permiso operativo: ${
                            if (controlesPuertaHabilitados) "Apertura, cierre y alarma habilitados para sala"
                            else "Operación suspendida temporalmente por Supervisión"
                        }",
                        fontSize = 13.sp,
                        color = Color(0xFF475569)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Administración de reglas y credenciales físicas reservada al Supervisor.",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }
        }

        // -------------------------------------------------------------------------
        // 6. BITÁCORA DE MOVIMIENTOS (AUDITORÍA COMPLETA PARA SUPERVISOR / OPERATIVA PARA OPERADOR)
        // -------------------------------------------------------------------------
        val eventosVisibles = remember(listaEventos, esSupervisor) {
            listaEventos.filter {
                SecurityUtils.esEventoOperativoVisible(it.usuarioRol, it.detalle, esSupervisor)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = if (esSupervisor) "Bitácora de Movimientos y Auditoría"
                    else "Bitácora Operativa de Accesos",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(eventosVisibles) { evento ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7FA)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = evento.usuarioRol,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = evento.fechaHora,
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = evento.detalle.replaceFirstChar { it.uppercase() },
                                    fontSize = 12.sp,
                                    color = Color(0xFF1E293B)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
