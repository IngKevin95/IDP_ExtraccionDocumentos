package com.idp.notification.net;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifica la propiedad del dominio consultando TXT {@code _idp-verify.<dominio>} (y de sus ancestros que no sean
 * sufijos publicos) con el DNS del sistema via JNDI. Un fallo de resolucion equivale a "no verificado".
 */
public final class DnsDomainOwnershipVerifier implements DomainOwnershipVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(DnsDomainOwnershipVerifier.class);

    @Override
    public boolean owns(String domain, String token) {
        String expected = DomainOwnershipVerifier.challengeValue(token);
        for (String candidate : candidates(domain)) {
            if (matches(txtValues(DomainOwnershipVerifier.challengeName(candidate)), expected)) {
                return true;
            }
        }
        return false;
    }

    /** El dominio y sus ancestros hasta (sin incluir) el sufijo publico. */
    static List<String> candidates(String domain) {
        List<String> out = new ArrayList<>();
        String d = domain.strip().toLowerCase(Locale.ROOT);
        while (!PublicSuffixes.isPublicSuffix(d)) {
            out.add(d);
            d = d.substring(d.indexOf('.') + 1);
        }
        return out;
    }

    /** true si algun TXT (con o sin comillas) coincide exactamente con el valor esperado. */
    static boolean matches(Collection<String> txtValues, String expected) {
        for (String v : txtValues) {
            if (v != null && stripQuotes(v.strip()).equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static String stripQuotes(String v) {
        String s = v;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\" \"", "");
    }

    private static List<String> txtValues(String name) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("java.naming.provider.url", "dns:");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        List<String> out = new ArrayList<>();
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(name, new String[] {"TXT"});
            Attribute txt = attrs.get("TXT");
            if (txt != null) {
                NamingEnumeration<?> all = txt.getAll();
                while (all.hasMore()) {
                    out.add(String.valueOf(all.next()));
                }
            }
        } catch (NamingException e) {
            LOG.debug("Sin TXT de verificacion para {}: {}", name, e.getClass().getSimpleName());
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (NamingException ignored) {
                    // nada que hacer al cerrar
                }
            }
        }
        return out;
    }
}
