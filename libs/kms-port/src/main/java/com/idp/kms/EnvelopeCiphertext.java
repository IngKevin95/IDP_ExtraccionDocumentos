package com.idp.kms;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Resultado del cifrado de sobre: DEK envuelta + datos cifrados (iv || ciphertext+tag). */
public record EnvelopeCiphertext(byte[] wrappedDek, byte[] encryptedData) {

    public EnvelopeCiphertext {
        wrappedDek = wrappedDek.clone();
        encryptedData = encryptedData.clone();
    }

    @Override
    public byte[] wrappedDek() {
        return wrappedDek.clone();
    }

    @Override
    public byte[] encryptedData() {
        return encryptedData.clone();
    }

    /** Formato: [len wrappedDek:int][wrappedDek][encryptedData]. */
    public byte[] toBytes() {
        // addExact: una suma que desborde int debe fallar, no producir un buffer de tamano incorrecto.
        int total = Math.addExact(Math.addExact(Integer.BYTES, wrappedDek.length), encryptedData.length);
        ByteBuffer b = ByteBuffer.allocate(total);
        b.putInt(wrappedDek.length).put(wrappedDek).put(encryptedData);
        return b.array();
    }

    public static EnvelopeCiphertext fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new IllegalArgumentException("Sobre invalido");
        }
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int len = b.getInt();
        if (len < 0 || len > b.remaining()) {
            throw new IllegalArgumentException("Sobre invalido");
        }
        byte[] wrapped = new byte[len];
        b.get(wrapped);
        byte[] data = new byte[b.remaining()];
        b.get(data);
        return new EnvelopeCiphertext(wrapped, data);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EnvelopeCiphertext e
            && Arrays.equals(wrappedDek, e.wrappedDek) && Arrays.equals(encryptedData, e.encryptedData);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(wrappedDek) + Arrays.hashCode(encryptedData);
    }

    @Override
    public String toString() {
        return "EnvelopeCiphertext[wrappedDek=" + wrappedDek.length + "B, encryptedData=" + encryptedData.length + "B]";
    }
}
