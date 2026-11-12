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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accessguardiot.ui.theme.AccessGuardIoTTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Actividad Principal con navegación y pantallas en Jetpack Compose (Material 3)
 */
class MainActivity : ComponentActivity() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var wifiManager: WifiSocketManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        dbHelper = DatabaseHelper(this)
        wifiManager = WifiSocketManager(this)

        setContent {
            AccessGuardIoTTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AccessGuardApp(
                        dbHelper = dbHelper,
                        wifiManager = wifiManager,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiManager.detenerServidor()
    }
}

@Composable
fun AccessGuardApp(
    dbHelper: DatabaseHelper,
    wifiManager: WifiSocketManager,
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

    // Estados de red Wi-Fi / Hotspot y cifrado AES-128
    var ipLocal by remember { mutableStateOf(wifiManager.obtenerIpLocal()) }
    var ipDestino by remember { mutableStateOf(estadoInicialSQLite.ultimaIpDestino) }
    var estadoConexion by remember { mutableStateOf("Escuchando en puerto 5000") }
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

    // Lista de bitácora y operadores cargados desde SQLite
    var listaEventos by remember { mutableStateOf(dbHelper.obtenerEventos()) }
    var listaOperadores by remember { mutableStateOf(dbHelper.obtenerOperadores()) }

    // Función que construye el paquete completo (estado actual + historial SQLite + usuarios) para sincronización
    fun construirEstadoParaSync(
        esSyncInicial: Boolean = true,
        rolEmisor: String = rolActivo ?: "SISTEMA",
        detalle: String = "Sincronización de estado e historial SQLite"
    ): EstadoSincronizadoIoT {
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
            claveSupervisor = dbHelper.obtenerPasswordSupervisor()
        )
    }

    // Iniciar el ServerSocket(5000) y la autoconexión/autodescubrimiento desde que se abre la app
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
                            "SEGURIDAD OT",
                            "Sesión cerrada automáticamente: cuenta '$usuarioEnSesion' eliminada por el Supervisor"
                        )
                        avisoSesionRevocada = "Sesión cerrada: tu cuenta '$usuarioEnSesion' fue eliminada por el Supervisor."
                        rolActivo = null
                        usuarioActivo = null
                    }
                }

                // 3. Actualizar controles si es un movimiento en vivo o si el otro dispositivo tiene un estado más reciente
                if (!estadoRemoto.esSincronizacionInicial || estadoRemoto.timestampModificacion >= timestampModificacion) {
                    puertaAbierta = estadoRemoto.puertaAbierta
                    anguloServo = estadoRemoto.anguloServo
                    buzzerActivo = estadoRemoto.buzzerActivo
                    modoAcceso = estadoRemoto.modoAcceso
                    uidRfid = estadoRemoto.uidRfid
                    pinBotonera = estadoRemoto.pinBotonera
                    timestampModificacion = estadoRemoto.timestampModificacion
                    actualizarPanelSQLite = true
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
            }
        )
    }

    // Función auxiliar que guarda cada movimiento en SQLite y lo envía cifrado con AES-128 por el socket bidireccional
    fun registrarYSincronizar(
        nuevaPuerta: Boolean = puertaAbierta,
        nuevoAngulo: Int = anguloServo,
        nuevoBuzzer: Boolean = buzzerActivo,
        nuevoModo: String = modoAcceso,
        nuevoUid: String = uidRfid,
        nuevoPin: String = pinBotonera,
        esCambioOperadores: Boolean = false,
        detalleAccion: String,
        rolOverride: String? = null
    ) {
        val rol = rolOverride ?: rolActivo ?: "SISTEMA"
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

        // 1. Guardar evento e estado actual en SQLite local (acceso_iot.db)
        dbHelper.insertarEvento(rol, detalleAccion)
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

        // 2. Enviar trama cifrada AES-128 (incluyendo los últimos registros SQLite y usuarios) por el Socket activo
        val estadoActual = EstadoSincronizadoIoT(
            puertaAbierta = nuevaPuerta,
            anguloServo = nuevoAngulo,
            buzzerActivo = nuevoBuzzer,
            modoAcceso = nuevoModo,
            uidRfid = nuevoUid,
            pinBotonera = nuevoPin,
            rolEmisor = rol,
            detalleEvento = detalleAccion,
            timestampModificacion = ahora,
            esSincronizacionInicial = false,
            historialEventos = dbHelper.obtenerEventosParaSincronizar(30),
            operadoresRegistrados = listaOperadores,
            timestampOperadores = timestampOperadores,
            claveSupervisor = dbHelper.obtenerPasswordSupervisor()
        )
        wifiManager.enviarCambio(ipDestino, estadoActual) { mensajeRed, tramaAES ->
            estadoConexion = mensajeRed
            if (tramaAES.isNotBlank()) {
                ultimaTramaAES = tramaAES
            }
        }
    }

    if (rolActivo == null) {
        LoginScreen(
            dbHelper = dbHelper,
            ipLocal = ipLocal,
            ipDestino = ipDestino,
            estadoConexion = estadoConexion,
            enlaceActivo = enlaceActivo,
            ipEnlazada = ipEnlazada,
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
            onRestablecerPassword = { user, codigoAuth, nuevaPass ->
                val uLimpio = user.trim()
                if (!dbHelper.existeUsuario(uLimpio)) {
                    false to "El usuario '$uLimpio' no existe en SQLite local."
                } else {
                    val errorValidacion = SecurityUtils.validarRestablecimientoPassword(
                        username = uLimpio,
                        codigoAutorizacion = codigoAuth,
                        nuevaPassword = nuevaPass,
                        passwordSupervisorActual = dbHelper.obtenerPasswordSupervisor()
                    )
                    if (errorValidacion != null) {
                        false to errorValidacion
                    } else {
                        val rolActualizado = dbHelper.actualizarPasswordUsuario(uLimpio, nuevaPass)
                        if (rolActualizado != null) {
                            registrarYSincronizar(
                                esCambioOperadores = true,
                                rolOverride = "SEGURIDAD OT",
                                detalleAccion = "restableció contraseña de '$uLimpio' ($rolActualizado) desde Login"
                            )
                            true to "Contraseña de '$uLimpio' actualizada en SQLite y sincronizada por Puerto 5000."
                        } else {
                            false to "No se pudo actualizar la contraseña en SQLite."
                        }
                    }
                }
            },
            onLoginExitoso = { rolAutenticado, usuarioAutenticado ->
                ipLocal = wifiManager.obtenerIpLocal()
                avisoSesionRevocada = null
                usuarioActivo = usuarioAutenticado
                rolActivo = rolAutenticado
                dbHelper.insertarEvento(rolAutenticado, "Inició sesión en el panel móvil ($usuarioAutenticado)")
                listaEventos = dbHelper.obtenerEventos()
                listaOperadores = dbHelper.obtenerOperadores()
            },
            modifier = modifier
        )
    } else {
        DashboardScreen(
            rolActivo = rolActivo!!,
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
            ultimaTramaAES = ultimaTramaAES,
            listaEventos = listaEventos,
            listaOperadores = listaOperadores,
            onRegistrarOperador = { nuevoUser, nuevaPass ->
                val creado = dbHelper.registrarOperador(nuevoUser, nuevaPass)
                if (creado) {
                    registrarYSincronizar(
                        esCambioOperadores = true,
                        detalleAccion = "creó cuenta de Operador ($nuevoUser)"
                    )
                }
                creado
            },
            onActualizarPasswordOperador = { userExistente, nuevaPass ->
                val actualizado = dbHelper.actualizarPasswordOperador(userExistente, nuevaPass)
                if (actualizado) {
                    registrarYSincronizar(
                        esCambioOperadores = true,
                        detalleAccion = "actualizó contraseña del Operador ($userExistente)"
                    )
                }
                actualizado
            },
            onEliminarOperador = { userEliminar ->
                val eliminado = dbHelper.eliminarOperador(userEliminar)
                if (eliminado) {
                    registrarYSincronizar(
                        esCambioOperadores = true,
                        detalleAccion = "eliminó cuenta de Operador ($userEliminar)"
                    )
                }
                eliminado
            },
            onActualizarEstado = { p, a, b, m, u, pin, det ->
                registrarYSincronizar(p, a, b, m, u, pin, false, det)
            },
            onCerrarSesion = {
                rolActivo?.let { dbHelper.insertarEvento(it, "Cerró sesión") }
                listaEventos = dbHelper.obtenerEventos()
                avisoSesionRevocada = null
                usuarioActivo = null
                rolActivo = null
            },
            modifier = modifier
        )
    }
}

// =========================================================================================
// BARRA PERSISTENTE DE ESTADO DE CONEXIÓN BIDIRECCIONAL EN PUERTO 5000
// =========================================================================================
@Composable
fun BarraEstadoConexionIoT(
    enlaceActivo: Boolean,
    ipEnlazada: String?,
    modifier: Modifier = Modifier
) {
    val fondo = if (enlaceActivo) Color(0xFFE8F5E9) else Color(0xFFFFF8E1)
    val borde = if (enlaceActivo) Color(0xFF4CAF50) else Color(0xFFFFB300)
    val colorTexto = if (enlaceActivo) Color(0xFF1B5E20) else Color(0xFFE65100)
    val mensaje = if (enlaceActivo) {
        "🟢 Conectado en Puerto 5000 | Sincronización Automática Activa (IP: ${ipEnlazada ?: "Red Local"})"
    } else {
        "🔄 Buscando / Reconectando automáticamente en Puerto 5000..."
    }

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
// PANTALLA 1: LOGIN CON VALIDACIÓN ISO/IEC 27400 Y BLOQUEO TRAS 3 INTENTOS FALLIDOS
// =========================================================================================
@Composable
fun LoginScreen(
    dbHelper: DatabaseHelper,
    ipLocal: String,
    ipDestino: String,
    estadoConexion: String,
    enlaceActivo: Boolean,
    ipEnlazada: String?,
    avisoSesionRevocada: String?,
    onLimpiarAvisoSesion: () -> Unit,
    onRefrescarIpLocal: () -> Unit,
    onIpDestinoChange: (String) -> Unit,
    onConectarYSincronizar: () -> Unit,
    onRestablecerPassword: (username: String, codigoAutorizacion: String, nuevaPassword: String) -> Pair<Boolean, String>,
    onLoginExitoso: (rol: String, username: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var usuario by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var mensajeError by rememberSaveable { mutableStateOf<String?>(null) }
    var esAlertaIso by rememberSaveable { mutableStateOf(false) }

    // Estados para "¿Olvidaste tu contraseña? / Restablecer Contraseña"
    var mostrarRecuperacion by rememberSaveable { mutableStateOf(false) }
    var usuarioRecuperar by rememberSaveable { mutableStateOf("") }
    var codigoAutorizacion by rememberSaveable { mutableStateOf("") }
    var nuevaPasswordRecuperar by rememberSaveable { mutableStateOf("") }
    var mensajeRecuperacion by rememberSaveable { mutableStateOf<String?>(null) }
    var recuperacionOk by rememberSaveable { mutableStateOf(false) }

    // Control de bloqueo temporal tras 3 intentos fallidos
    var intentosFallidos by rememberSaveable { mutableIntStateOf(0) }
    var segundosBloqueo by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(segundosBloqueo) {
        if (segundosBloqueo > 0) {
            delay(1000)
            segundosBloqueo--
            if (segundosBloqueo == 0) {
                intentosFallidos = 0
                mensajeError = null
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BarraEstadoConexionIoT(
            enlaceActivo = enlaceActivo,
            ipEnlazada = ipEnlazada
        )
        Spacer(modifier = Modifier.height(14.dp))

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
                    text = "Control de Acceso y Monitoreo OT",
                    fontSize = 13.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(20.dp))

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
                    enabled = segundosBloqueo == 0,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = segundosBloqueo == 0,
                    modifier = Modifier.fillMaxWidth()
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

                Button(
                    onClick = {
                        // 1. Validar Norma ISO/IEC 27400 (Prohibición de credenciales por defecto)
                        val credencialCombinada = "${usuario.trim()}/${password.trim()}"
                        if (SecurityUtils.esCredencialProhibida(usuario) ||
                            SecurityUtils.esCredencialProhibida(password) ||
                            SecurityUtils.esCredencialProhibida(credencialCombinada)
                        ) {
                            esAlertaIso = true
                            intentosFallidos++
                            dbHelper.insertarEvento(
                                "SEGURIDAD ISO",
                                "Intento bloqueado con clave genérica prohibida ($usuario)"
                            )
                            if (intentosFallidos >= 3) {
                                segundosBloqueo = 15
                                mensajeError = "BLOQUEO TEMPORAL (15s): 3 intentos fallidos detectados."
                            } else {
                                mensajeError = "ALERTA ROJA ISO/IEC 27400: Credencial por defecto (admin / 1234 / 0000) rechazada por seguridad IoT. Intentos: $intentosFallidos/3"
                            }
                            return@Button
                        }

                        // 2. Validar contra la tabla usuarios en SQLite
                        val rol = dbHelper.validarUsuario(usuario, password)
                        if (rol != null) {
                            intentosFallidos = 0
                            mensajeError = null
                            onLimpiarAvisoSesion()
                            onLoginExitoso(rol, usuario.trim())
                        } else {
                            esAlertaIso = false
                            intentosFallidos++
                            if (intentosFallidos >= 3) {
                                segundosBloqueo = 15
                                dbHelper.insertarEvento(
                                    "SEGURIDAD",
                                    "Login bloqueado temporalmente tras 3 intentos fallidos"
                                )
                                mensajeError = "Acceso bloqueado por 15 segundos tras 3 intentos fallidos."
                            } else {
                                mensajeError = "Usuario o contraseña incorrectos. Intento $intentosFallidos de 3."
                            }
                        }
                    },
                    enabled = segundosBloqueo == 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (segundosBloqueo > 0) "Bloqueado temporalmente (${segundosBloqueo}s)"
                        else "Ingresar al Panel IoT"
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                TextButton(
                    onClick = {
                        mostrarRecuperacion = !mostrarRecuperacion
                        if (mostrarRecuperacion && usuarioRecuperar.isBlank() && usuario.isNotBlank()) {
                            usuarioRecuperar = usuario.trim()
                        }
                        if (!mostrarRecuperacion) {
                            mensajeRecuperacion = null
                        }
                    }
                ) {
                    Text(
                        text = if (mostrarRecuperacion) "Ocultar Restablecimiento de Contraseña"
                        else "¿Olvidaste tu contraseña? / Restablecer Contraseña",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (mostrarRecuperacion) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = "Restablecer Contraseña (ISO/IEC 27400)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = usuarioRecuperar,
                        onValueChange = { usuarioRecuperar = it },
                        label = { Text("Usuario a restablecer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = codigoAutorizacion,
                        onValueChange = { codigoAutorizacion = it },
                        label = { Text("Código de Autorización OT") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = nuevaPasswordRecuperar,
                        onValueChange = { nuevaPasswordRecuperar = it },
                        label = { Text("Nueva Contraseña (ISO/IEC 27400)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Autorización OT (código OT o clave Supervisor vigente) + clave segura de 8+ caracteres con mayúscula, minúscula y número.",
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
                                segundosBloqueo = 0
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Actualizar Contraseña y Sincronizar")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Tarjeta compacta de sincronización Wi-Fi Pre-Login (recibe nuevas cuentas antes de iniciar sesión)
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
                            text = "Sincronización Pre-Login (Wi-Fi Puerto 5000)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Mi IP Local: $ipLocal",
                            fontSize = 11.sp,
                            color = Color(0xFF1565C0),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(onClick = onRefrescarIpLocal) {
                        Text("Ver mi IP", fontSize = 11.sp)
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
                        label = { Text("IP del Supervisor") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onConectarYSincronizar) {
                        Text("Sincronizar", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Estado Red: $estadoConexion",
                    fontSize = 11.sp,
                    color = Color(0xFF424242)
                )
            }
        }
    }
}

// =========================================================================================
// PANTALLA 2: PANEL PRINCIPAL SEGÚN ROL (SUPERVISOR VS. OPERADOR)
// =========================================================================================
@Composable
fun DashboardScreen(
    rolActivo: String,
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
    ultimaTramaAES: String,
    listaEventos: List<EventoBitacora>,
    listaOperadores: List<UsuarioOperador>,
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // -------------------------------------------------------------------------
        // 0. INDICADOR VISUAL PERMANENTE DE ENLACE AUTOMÁTICO EN PUERTO 5000
        // -------------------------------------------------------------------------
        BarraEstadoConexionIoT(
            enlaceActivo = enlaceActivo,
            ipEnlazada = ipEnlazada
        )

        // -------------------------------------------------------------------------
        // 1. ENCABEZADO CLARO DEL ROL ACTIVO + IP LOCAL HOTSPOT/WI-FI + CERRAR SESIÓN
        // -------------------------------------------------------------------------
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (esSupervisor) Color(0xFF1A237E) else Color(0xFF004D40)
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
                        text = if (esSupervisor) "MODO SUPERVISOR OT (Control Total)"
                        else "MODO OPERADOR DE SALA (Control Básico)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Mi IP Local (Hotspot / Wi-Fi): $ipLocal",
                        color = Color(0xFFFFF59D),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Puerto TCP Bidireccional: 5000",
                        color = Color(0xFFE0E0E0),
                        fontSize = 11.sp
                    )
                }
                Button(
                    onClick = onCerrarSesion,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Cerrar Sesión", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // -------------------------------------------------------------------------
        // 2. CONEXIÓN BIDIRECCIONAL PERSISTENTE Y SINCRONIZACIÓN DE HISTORIAL SQLITE
        // -------------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7FA))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Canal Socket Wi-Fi Bidireccional (Emulador <-> Teléfono)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onRefrescarIpLocal
                    ) {
                        Text("Ver mi IP", fontSize = 11.sp)
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
                        label = { Text("IP del Teléfono (Hotspot / Wi-Fi)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onConectarYSincronizar
                    ) {
                        Text("Conectar /\nSincronizar", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Estado Red: $estadoConexion",
                    fontSize = 12.sp,
                    color = Color(0xFF1565C0),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // -------------------------------------------------------------------------
        // 3. TARJETA DE MONITOREO EN VIVO (CERROJO SG90, BUZZER, MODO Y TRAMA AES)
        // -------------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Monitoreo en Vivo - Sala de Servidores",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Cerrojo Servomotor SG90:", fontWeight = FontWeight.Medium)
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (puertaAbierta) Color(0xFF2E7D32) else Color(0xFFC62828),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (puertaAbierta) "ABIERTO ($anguloServo°)" else "CERRADO ($anguloServo°)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• Alarma Buzzer: ${if (buzzerActivo) "ACTIVA (Sonando)" else "SILENCIADA"}",
                    fontSize = 13.sp
                )
                Text(
                    text = "• Modo Activo: ${
                        when (modoAcceso) {
                            "RFID" -> "Lector RFID-RC522 (Solo Tarjeta)"
                            "BOTONERA" -> "Botonera Matricial 4x4 (Solo PIN)"
                            "RFID_PIN" -> "Doble Factor 2FA (RFID + PIN 4x4)"
                            else -> "BLOQUEO LOCKDOWN OT"
                        }
                    }",
                    fontWeight = FontWeight.SemiBold,
                    color = if (enLockdown) Color(0xFFC62828) else Color.Unspecified,
                    fontSize = 13.sp
                )
                Text(
                    text = "• Credenciales Vigentes -> UID RFID: $uidRfid | PIN 4x4: $pinBotonera",
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Última trama AES-128 (Base64): ${ultimaTramaAES.take(64)}...",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF424242)
                )
            }
        }

        // -------------------------------------------------------------------------
        // 4. CONTROLES DE PUERTA (SUPERVISOR Y OPERADOR, SALVO EN LOCKDOWN)
        // -------------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Controles Operativos de Puerta (SG90 y Buzzer)",
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
                            text = "🔒 Puerta bloqueada por el Supervisor OT (Modo Lockdown activo)",
                            color = Color(0xFFB71C1C),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Switch: Abrir / Cerrar cerrojo SG90
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Abrir / Cerrar Cerrojo SG90")
                    Switch(
                        checked = puertaAbierta,
                        enabled = controlesPuertaHabilitados,
                        onCheckedChange = { abrir ->
                            val nuevoAngulo = if (abrir) 90 else 0
                            val accion = if (abrir) "abrió la puerta a 90°" else "cerró la puerta a 0°"
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

                // Slider (0° a 180°): Ajustar ángulo de apertura del servomotor
                Text(text = "Ángulo Servomotor SG90: ${sliderAngulo.roundToInt()}° (0° a 180°)")
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
                            "ajustó el ángulo del servomotor a $anguloFinal°"
                        )
                    },
                    valueRange = 0f..180f,
                    enabled = controlesPuertaHabilitados
                )

                // Checkbox: Activar / Silenciar alarma del Buzzer
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = buzzerActivo,
                        enabled = controlesPuertaHabilitados,
                        onCheckedChange = { activo ->
                            val accion = if (activo) "activó la alarma del Buzzer" else "silenció la alarma del Buzzer"
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
                    Text(text = "Activar alarma sonora (Buzzer)")
                }
            }
        }

        // -------------------------------------------------------------------------
        // 5. CONTROLES EXCLUSIVOS DEL SUPERVISOR (BLOQUEADOS CON CANDADO PARA OPERADOR)
        // -------------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (esSupervisor) MaterialTheme.colorScheme.surface else Color(0xFFEEEEEE)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = if (esSupervisor) "Controles Exclusivos del Supervisor OT"
                    else "🔒 Controles del Supervisor (Solo Lectura para Operador)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (esSupervisor) Color(0xFF1A237E) else Color.DarkGray
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(text = "Modo de Operación del Acceso:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                // RadioButton 1: RFID-RC522
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = (modoAcceso == "RFID"),
                        enabled = esSupervisor,
                        onClick = {
                            onActualizarEstado(
                                puertaAbierta,
                                anguloServo,
                                buzzerActivo,
                                "RFID",
                                uidRfid,
                                pinBotonera,
                                "cambió modo de acceso a RFID-RC522"
                            )
                        }
                    )
                    Text(text = "RFID-RC522 (Tarjeta / Llavero)", fontSize = 13.sp)
                }

                // RadioButton 2: Botonera 4x4
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = (modoAcceso == "BOTONERA"),
                        enabled = esSupervisor,
                        onClick = {
                            onActualizarEstado(
                                puertaAbierta,
                                anguloServo,
                                buzzerActivo,
                                "BOTONERA",
                                uidRfid,
                                pinBotonera,
                                "cambió modo de acceso a Botonera 4x4"
                            )
                        }
                    )
                    Text(text = "Botonera Matricial 4x4 (Código PIN)", fontSize = 13.sp)
                }

                // RadioButton 3: Doble Factor (Exigir RFID + PIN 4x4 para evitar clonación)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = (modoAcceso == "RFID_PIN"),
                        enabled = esSupervisor,
                        onClick = {
                            onActualizarEstado(
                                puertaAbierta,
                                anguloServo,
                                buzzerActivo,
                                "RFID_PIN",
                                uidRfid,
                                pinBotonera,
                                "activó Doble Factor 2FA (Exigir RFID + PIN 4x4)"
                            )
                        }
                    )
                    Text(
                        text = "Doble Factor 2FA (Exigir RFID + PIN 4x4)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1565C0)
                    )
                }

                // RadioButton 4: Bloqueo Lockdown OT (Cierra a 0° y bloquea al Operador)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = (modoAcceso == "LOCKDOWN"),
                        enabled = esSupervisor,
                        onClick = {
                            // Al activar Lockdown, se cierra la puerta a 0° y se bloquea al Operador
                            onActualizarEstado(
                                false,
                                0,
                                buzzerActivo,
                                "LOCKDOWN",
                                uidRfid,
                                pinBotonera,
                                "activó Bloqueo Lockdown OT (Puerta cerrada a 0°)"
                            )
                        }
                    )
                    Text(
                        text = "Bloqueo Lockdown OT (Cierra a 0° y bloquea al Operador)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFC62828)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Actualización de Claves de Acceso (Validación ISO/IEC 27400):",
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
                        label = { Text("UID RFID Autorizado") },
                        enabled = esSupervisor,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = campoPin,
                        onValueChange = { campoPin = it },
                        label = { Text("PIN Botonera 4x4") },
                        enabled = esSupervisor,
                        singleLine = true,
                        modifier = Modifier.width(140.dp)
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
                        val errorValidacion = SecurityUtils.validarCredencialesAcceso(campoUid, campoPin)
                        if (errorValidacion != null) {
                            claveActualizadaOk = false
                            mensajeClaveIso = errorValidacion
                        } else {
                            claveActualizadaOk = true
                            mensajeClaveIso = "Credenciales actualizadas y sincronizadas con éxito."
                            onActualizarEstado(
                                puertaAbierta,
                                anguloServo,
                                buzzerActivo,
                                modoAcceso,
                                campoUid.trim(),
                                campoPin.trim(),
                                "actualizó credenciales (UID: ${campoUid.trim()}, PIN: ${campoPin.trim()})"
                            )
                        }
                    },
                    enabled = esSupervisor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (esSupervisor) "Actualizar UID RFID y PIN 4x4"
                        else "🔒 Solo el Supervisor puede modificar claves"
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // -----------------------------------------------------------------
                // CRUD DE USUARIOS SQLITE: CREAR, LISTAR, EDITAR CLAVE Y ELIMINAR (SOLO SUPERVISOR)
                // -----------------------------------------------------------------
                Text(
                    text = "CRUD de Usuarios Operadores (SQLite - Gestión Centralizada):",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Lista de Operadores registrados con botones de Editar Clave (Update) y Eliminar (Delete)
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
                                        text = "Rol: OPERADOR",
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }

                                if (esSupervisor) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                usuarioOpEditando = op.username
                                                nuevoUsuarioOp = op.username
                                                nuevaPassOp = ""
                                                mensajeRegistroOp = "Editando clave de '${op.username}'. Ingrese la nueva contraseña abajo."
                                                registroOpOk = true
                                            }
                                        ) {
                                            Text("Editar Clave", fontSize = 11.sp)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                if (listaOperadores.size <= 1) {
                                                    registroOpOk = false
                                                    mensajeRegistroOp = "Seguridad OT: Debe mantenerse al menos 1 cuenta de Operador activa."
                                                } else {
                                                    val borrado = onEliminarOperador(op.username)
                                                    if (borrado) {
                                                        if (usuarioOpEditando == op.username) {
                                                            usuarioOpEditando = null
                                                            nuevoUsuarioOp = ""
                                                            nuevaPassOp = ""
                                                        }
                                                        registroOpOk = true
                                                        mensajeRegistroOp = "Operador '${op.username}' eliminado de SQLite y sincronizado."
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
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (usuarioOpEditando != null)
                        "✏️ Editando contraseña del Operador: $usuarioOpEditando"
                    else
                        "➕ Registrar nuevo Operador en SQLite:",
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = if (usuarioOpEditando != null) Color(0xFF1565C0) else Color.Unspecified
                )
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = nuevoUsuarioOp,
                        onValueChange = { nuevoUsuarioOp = it },
                        label = { Text("Usuario Operador") },
                        enabled = esSupervisor && (usuarioOpEditando == null),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = nuevaPassOp,
                        onValueChange = { nuevaPassOp = it },
                        label = {
                            Text(if (usuarioOpEditando != null) "Nueva Contraseña" else "Contraseña")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = esSupervisor,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
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
                            val uLimpio = (usuarioOpEditando ?: nuevoUsuarioOp).trim()
                            val pLimpia = nuevaPassOp.trim()
                            val errorUsuario = SecurityUtils.validarNuevoOperador(uLimpio, pLimpia)

                            if (errorUsuario != null) {
                                registroOpOk = false
                                mensajeRegistroOp = errorUsuario
                            } else if (usuarioOpEditando != null) {
                                // CRUD Update: Cambiar contraseña del Operador seleccionado
                                val actualizado = onActualizarPasswordOperador(uLimpio, pLimpia)
                                if (actualizado) {
                                    registroOpOk = true
                                    mensajeRegistroOp = "Contraseña del Operador '$uLimpio' actualizada en SQLite y sincronizada."
                                    usuarioOpEditando = null
                                    nuevoUsuarioOp = ""
                                    nuevaPassOp = ""
                                } else {
                                    registroOpOk = false
                                    mensajeRegistroOp = "No se pudo actualizar la contraseña de '$uLimpio'."
                                }
                            } else {
                                // CRUD Create: Crear nuevo Operador
                                val exito = onRegistrarOperador(uLimpio, pLimpia)
                                if (exito) {
                                    registroOpOk = true
                                    mensajeRegistroOp = "Operador '$uLimpio' creado en SQLite con éxito (Rol: OPERADOR)."
                                    nuevoUsuarioOp = ""
                                    nuevaPassOp = ""
                                } else {
                                    registroOpOk = false
                                    mensajeRegistroOp = "El usuario '$uLimpio' ya existe en SQLite. Usa 'Editar Clave' arriba si deseas cambiar su contraseña."
                                }
                            }
                        },
                        enabled = esSupervisor,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when {
                                !esSupervisor -> "🔒 Solo el Supervisor administra cuentas"
                                usuarioOpEditando != null -> "Guardar Nueva Clave"
                                else -> "Crear Nuevo Operador"
                            }
                        )
                    }

                    if (esSupervisor && usuarioOpEditando != null) {
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
            }
        }

        // -------------------------------------------------------------------------
        // 6. BITÁCORA DE MOVIMIENTOS (LAZYCOLUMN DESDE SQLITE)
        // -------------------------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Bitácora de Movimientos (SQLite: acceso_iot.db)",
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
                    items(listaEventos) { evento ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "[${evento.fechaHora}] ${evento.usuarioRol} ${evento.detalle}",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
