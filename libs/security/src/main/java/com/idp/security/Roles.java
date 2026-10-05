package com.idp.security;

import java.util.Set;

/**
 * Nombres canonicos de rol (docs/producto.md, seccion 3). Son los valores de {@code role_assignment.role} en la base
 * de control y los que revalida cada servicio; ningun servicio define roles propios.
 */
public final class Roles {

    /** Sistema Integrador / operador: carga documentos y consulta estado y datos extraidos de su tenant. */
    public static final String OPERADOR = "OPERADOR";
    /** Revisor HITL: corrige o propone correcciones de campos dudosos. */
    public static final String REVISOR = "REVISOR";
    /** Data Steward: golden set, aprobacion de documentos altamente confidenciales. */
    public static final String DATA_STEWARD = "DATA_STEWARD";
    /** Auditoria: lee expedientes WORM y trazas de hash-chain. */
    public static final String AUDITOR = "AUDITOR";
    /** Oficial de Seguridad: logs de seguridad, incidentes y denegaciones; sin contenido documental. */
    public static final String OFICIAL_SEGURIDAD = "OFICIAL_SEGURIDAD";
    /** Soporte / break-glass: acceso temporal con justificacion, TTL y aprobador distinto. */
    public static final String SOPORTE = "BREAK_GLASS";
    /** Compliance / regulador: revisa registros inmutables frente a disputas. */
    public static final String COMPLIANCE = "COMPLIANCE";
    /** Riesgo de modelo: revisa drift, calidad y registro de ejecuciones de IA. */
    public static final String RIESGO_MODELO = "RIESGO_MODELO";
    /** Administrador de tenant: parametros, usuarios, cuotas y reportes del tenant. */
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    /** Administrador de plataforma: ciclo de vida de tenants (authority {@code ROLE_PLATFORM_ADMIN} en el token). */
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** Roles asignables dentro de un tenant (todos salvo el de plataforma). */
    public static final Set<String> TENANT_ROLES = Set.of(OPERADOR, REVISOR, DATA_STEWARD, AUDITOR,
        OFICIAL_SEGURIDAD, SOPORTE, COMPLIANCE, RIESGO_MODELO, TENANT_ADMIN);

    private Roles() {
    }
}
