package com.idp.tenant.domain;

/** Roles de un tenant. BREAK_GLASS solo se otorga por el flujo excepcional de plataforma. */
public enum TenantRole { TENANT_ADMIN, OPERADOR, REVISOR, APROBADOR, AUDITOR, BREAK_GLASS }
