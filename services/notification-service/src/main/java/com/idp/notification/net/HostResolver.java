package com.idp.notification.net;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Resolucion DNS inyectable: la implementacion de produccion es el resolvedor del sistema. */
@FunctionalInterface
public interface HostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;

    /** Resolvedor del sistema operativo (los literales IP no consultan DNS). */
    static HostResolver system() {
        return InetAddress::getAllByName;
    }
}
