package com.idp.notification.net;

import java.util.Locale;
import java.util.Set;

/**
 * Sufijos publicos para rechazar comodines y entradas de allowlist que cubririan dominios ajenos (por ejemplo
 * {@code *.com}, {@code *.co.uk}, {@code *.github.io}). No es la Public Suffix List completa: combina
 * (1) toda etiqueta unica (TLD), (2) una lista embebida de sufijos multi-etiqueta y de zonas compartidas de
 * proveedores cloud/hosting y (3) una heuristica para ccTLD de segundo nivel: dos etiquetas cuya ultima es un
 * codigo de pais (2 letras) y cuya primera es un segundo nivel generico ({@code co, com, org, net, gov, ...}).
 * Prefiere falsos positivos (rechazar de mas) a falsos negativos; un dominio legitimo rechazado se registra con
 * entradas exactas en lugar de comodin.
 */
public final class PublicSuffixes {

    /** Segundos niveles genericos que, bajo un ccTLD, son sufijos publicos (com.ar, co.uk, gob.pe...). */
    private static final Set<String> GENERIC_SECOND_LEVEL = Set.of("co", "com", "org", "net", "gov", "gob", "gub",
        "edu", "ac", "or", "ne", "go", "gv", "mil", "nom", "sch", "ltd", "plc", "int", "ed", "gouv");

    /** Zonas compartidas donde terceros distintos registran subdominios: nunca admiten comodin. */
    private static final Set<String> SHARED_ZONES = Set.of("github.io", "gitlab.io", "herokuapp.com", "vercel.app",
        "netlify.app", "pages.dev", "workers.dev", "cloudfront.net", "amazonaws.com", "elasticbeanstalk.com",
        "azurewebsites.net", "azureedge.net", "cloudapp.net", "windows.net", "appspot.com", "web.app",
        "firebaseapp.com", "run.app", "ngrok.io", "ngrok-free.app", "trycloudflare.com", "onrender.com", "fly.dev",
        "repl.co", "glitch.me", "myshopify.com", "wordpress.com", "blogspot.com", "weebly.com", "wixsite.com",
        "000webhostapp.com", "duckdns.org", "no-ip.org", "dyndns.org", "nip.io", "sslip.io", "xip.io");

    /** Sufijos multi-etiqueta frecuentes que la heuristica de ccTLD no cubre. */
    private static final Set<String> EXTRA_SUFFIXES = Set.of("co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk",
        "plc.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp", "ne.jp", "or.jp", "co.in", "co.za", "com.br",
        "com.mx", "com.ar", "com.co", "com.pe", "com.cl", "com.ec", "com.uy", "com.ve", "com.bo", "com.py",
        "com.cn", "com.hk", "com.sg", "com.tr", "com.tw", "com.es", "com.pt", "us.com", "eu.com", "uk.com",
        "k12.us", "co.cc");

    private PublicSuffixes() {
    }

    /** true si {@code domain} es un sufijo publico (o una etiqueta unica) y por tanto no puede ser dueno de nadie. */
    public static boolean isPublicSuffix(String domain) {
        String d = normalize(domain);
        if (d.isEmpty() || d.indexOf('.') < 0) {
            return true;
        }
        if (SHARED_ZONES.contains(d) || EXTRA_SUFFIXES.contains(d)) {
            return true;
        }
        String[] labels = d.split("\\.");
        return labels.length == 2 && labels[1].length() == 2 && GENERIC_SECOND_LEVEL.contains(labels[0]);
    }

    /**
     * true si el dominio base de un comodin {@code *.base} es admisible: no es sufijo publico ni cuelga de una zona
     * compartida de proveedor (cualquier tercero podria registrar un hermano bajo ella).
     */
    public static boolean wildcardBaseAllowed(String base) {
        String d = normalize(base);
        if (isPublicSuffix(d)) {
            return false;
        }
        for (String zone : SHARED_ZONES) {
            if (d.endsWith("." + zone)) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String domain) {
        String d = domain == null ? "" : domain.strip().toLowerCase(Locale.ROOT);
        while (d.endsWith(".")) {
            d = d.substring(0, d.length() - 1);
        }
        return d;
    }
}
