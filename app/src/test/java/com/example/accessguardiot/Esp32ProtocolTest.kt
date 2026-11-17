package com.example.accessguardiot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Esp32ProtocolTest {

    @Test
    fun formatearComando_genera_trama_estandar_para_esp32() {
        val cmd = Esp32Protocol.formatearComando(
            puertaAbierta = true,
            anguloServo = 135,
            buzzerActivo = false,
            modoAcceso = "rfid_pin",
            uidRfid = "A4-9B-3C-12",
            pinBotonera = "4829"
        )
        assertEquals(
            "CMD|PUERTA=1|ANGULO=135|BUZZER=0|MODO=RFID_PIN|UID=A4-9B-3C-12|PIN=4829",
            cmd
        )
    }

    @Test
    fun parsearMensaje_reconoce_ping_pong_rfid_pin_y_estado() {
        assertEquals(Esp32Protocol.MensajeEsp32.Ping, Esp32Protocol.parsearMensaje("PING"))
        assertEquals(Esp32Protocol.MensajeEsp32.Pong, Esp32Protocol.parsearMensaje("PONG"))
        assertEquals(
            Esp32Protocol.MensajeEsp32.TarjetaRfid("A4-9B-3C-12"),
            Esp32Protocol.parsearMensaje("RFID|A4-9B-3C-12")
        )
        assertEquals(
            Esp32Protocol.MensajeEsp32.PinBotonera("4829"),
            Esp32Protocol.parsearMensaje("PIN|4829")
        )

        val estado = Esp32Protocol.parsearMensaje("EVT|PUERTA=1|ANGULO=90|BUZZER=0|MODO=RFID")
        assertTrue(estado is Esp32Protocol.MensajeEsp32.Estado)
        val castEstado = estado as Esp32Protocol.MensajeEsp32.Estado
        assertEquals(true, castEstado.puertaAbierta)
        assertEquals(90, castEstado.anguloServo)
        assertEquals(false, castEstado.buzzerActivo)
        assertEquals("RFID", castEstado.modoAcceso)
    }

    @Test
    fun parsearDescubrimientoUdp_valida_prefijo_ip_y_puerto() {
        assertEquals(
            "192.168.43.88" to 5002,
            Esp32Protocol.parsearDescubrimientoUdp("ACCESSGUARD_ESP32|192.168.43.88|5002", "192.168.43.1")
        )
        assertEquals(
            "192.168.43.1" to Esp32Protocol.PUERTO_ESP32,
            Esp32Protocol.parsearDescubrimientoUdp("ACCESSGUARD_ESP32", "192.168.43.1")
        )
        assertNull(Esp32Protocol.parsearDescubrimientoUdp("OTRO_SERVICIO|192.168.43.88|5002", "192.168.43.1"))
    }

    @Test
    fun cifrarTrama_y_descifrarTrama_validan_integridad_mac_y_rechazan_adulteracion() {
        val comandoOriginal = "CMD|PUERTA=1|ANGULO=90|BUZZER=0|MODO=RFID|UID=A4-9B-3C-12|PIN=4829"
        val tramaCifrada = Esp32Protocol.cifrarTrama(comandoOriginal, nonceFijo = "A1B2")

        assertTrue(tramaCifrada.startsWith("SEC|A1B2|"))
        assertEquals(comandoOriginal, Esp32Protocol.descifrarTrama(tramaCifrada))

        val eventoCifrado = Esp32Protocol.cifrarTrama(
            "EVT|PUERTA=1|ANGULO=90|BUZZER=0|MODO=RFID|DETALLE=abrio puerta con tarjeta RFID (****-04)"
        )
        val parseado = Esp32Protocol.parsearMensaje(eventoCifrado)
        assertTrue(parseado is Esp32Protocol.MensajeEsp32.Estado)

        // Si se altera 1 carácter del payload o del MAC, descifrarTrama debe rechazarlo (null)
        val adulterada = tramaCifrada.dropLast(1) + if (tramaCifrada.last() == '0') '1' else '0'
        assertNull(Esp32Protocol.descifrarTrama(adulterada))
        assertNull(Esp32Protocol.parsearMensaje(adulterada))
    }
}
