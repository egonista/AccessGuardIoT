package com.example.accessguardiot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityUtilsTest {

    @Test
    fun hashPassword_y_verificacion_funcionan_con_sal_por_usuario() {
        val hashSup = SecurityUtils.hashPassword("supervisor", "Sup#2026IoT")
        val hashOp = SecurityUtils.hashPassword("operador", "Sup#2026IoT")

        assertTrue(SecurityUtils.esHashSha256(hashSup))
        assertNotEquals(hashSup, hashOp)
        assertTrue(SecurityUtils.verificarPassword("Supervisor", "Sup#2026IoT", hashSup))
        assertFalse(SecurityUtils.verificarPassword("supervisor", "OtraClave1", hashSup))
        assertEquals(hashSup, SecurityUtils.normalizarHashPassword("supervisor", hashSup))
    }

    @Test
    fun validacion_iso27400_rechaza_claves_debiles_y_acepta_claves_robustas() {
        assertNotNull(SecurityUtils.validarNuevoOperador("admin", "Operador2026"))
        assertNotNull(SecurityUtils.validarNuevoOperador("egon|admin", "Operador2026"))
        assertNotNull(SecurityUtils.validarNuevoOperador("carlos", "1234"))
        assertNotNull(SecurityUtils.validarNuevoOperador("carlos", "corta1A"))
        assertNull(SecurityUtils.validarNuevoOperador("carlos", "Carlos2026"))
        assertNotNull(SecurityUtils.validarCredencialesAcceso("A4-9B-3C-12", "1234"))
        assertNull(SecurityUtils.validarCredencialesAcceso("A4-9B-3C-12", "4829"))
    }

    @Test
    fun llave_recuperacion_aleatoria_y_hash_sha256_funcionan_sin_claves_fijas() {
        val llave1 = SecurityUtils.generarCodigoAutorizacionAleatorio()
        val llave2 = SecurityUtils.generarCodigoAutorizacionAleatorio()
        assertTrue(llave1.startsWith("AG-"))
        assertEquals(12, llave1.length)
        assertNotEquals(llave1, llave2)

        val hashLlave = SecurityUtils.hashCodigoAutorizacion(llave1)
        assertTrue(SecurityUtils.esHashSha256(hashLlave))
        assertTrue(SecurityUtils.verificarCodigoAutorizacion(llave1, hashLlave))
        assertTrue(SecurityUtils.verificarCodigoAutorizacion(llave1.lowercase(), hashLlave))
        assertFalse(SecurityUtils.verificarCodigoAutorizacion("OT#2026Seg", hashLlave))
        assertFalse(SecurityUtils.verificarCodigoAutorizacion(llave2, hashLlave))
    }

    @Test
    fun restablecimiento_exige_codigo_autorizacion_y_clave_robusta() {
        assertNotNull(
            SecurityUtils.validarRestablecimientoPassword(
                username = "carlos",
                codigoAutorizacion = "incorrecto",
                nuevaPassword = "NuevaClave2026",
                codigoAutorizacionValido = false,
                codigoSupervisorValido = false
            )
        )
        assertNull(
            SecurityUtils.validarRestablecimientoPassword(
                username = "carlos",
                codigoAutorizacion = "AG-7K4M-9P2Q",
                nuevaPassword = "NuevaClave2026",
                codigoAutorizacionValido = true,
                codigoSupervisorValido = false
            )
        )
        assertNull(
            SecurityUtils.validarRestablecimientoPassword(
                username = "carlos",
                codigoAutorizacion = "ClaveVigenteSup1",
                nuevaPassword = "NuevaClave2026",
                codigoAutorizacionValido = false,
                codigoSupervisorValido = true
            )
        )
    }

    @Test
    fun filtro_bitacora_oculta_gestion_de_cuentas_para_operador_y_muestra_todo_al_supervisor() {
        assertTrue(SecurityUtils.esEventoOperativoVisible("OPERADOR (Egon)", "abrió la puerta (90°)", esSupervisor = false))
        assertTrue(SecurityUtils.esEventoOperativoVisible("SUPERVISOR (supervisor)", "rotó credenciales físicas (Tarjeta ****-12, PIN ****)", esSupervisor = false))

        assertFalse(SecurityUtils.esEventoOperativoVisible("SUPERVISOR (supervisor)", "creó la cuenta de operador 'Renato'", esSupervisor = false))
        assertFalse(SecurityUtils.esEventoOperativoVisible("SUPERVISOR (supervisor)", "actualizó la contraseña del operador 'Renato'", esSupervisor = false))
        assertFalse(SecurityUtils.esEventoOperativoVisible("SUPERVISOR (supervisor)", "eliminó la cuenta del operador 'Renato'", esSupervisor = false))
        assertFalse(SecurityUtils.esEventoOperativoVisible("SUPERVISOR (supervisor)", "rotó la llave de autorización y recuperación", esSupervisor = false))
        assertFalse(SecurityUtils.esEventoOperativoVisible("SUPERVISOR (supervisor)", "actualizó la contraseña del Supervisor", esSupervisor = false))
        assertFalse(SecurityUtils.esEventoOperativoVisible("SEGURIDAD", "Intento bloqueado por uso de credencial restringida (admin)", esSupervisor = false))

        assertTrue(SecurityUtils.esEventoOperativoVisible("SUPERVISOR (supervisor)", "creó la cuenta de operador 'Renato'", esSupervisor = true))
        assertTrue(SecurityUtils.esEventoOperativoVisible("SUPERVISOR (supervisor)", "rotó la llave de autorización y recuperación", esSupervisor = true))
        assertTrue(SecurityUtils.esEventoOperativoVisible("SEGURIDAD", "Intento bloqueado por uso de credencial restringida (admin)", esSupervisor = true))
    }
}
