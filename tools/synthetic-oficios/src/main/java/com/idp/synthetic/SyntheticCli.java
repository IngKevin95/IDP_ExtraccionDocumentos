package com.idp.synthetic;

import com.idp.synthetic.BatchGenerator.Modo;
import com.idp.synthetic.BatchGenerator.Opciones;
import com.idp.synthetic.OficioGenerator.Plan;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CLI reproducible: misma semilla, mismos oficios.
 * Uso: java -jar synthetic-oficios-exec.jar --salida DIR [--semilla N] [--cantidad N] [--desde N]
 * [--tipologias EC,EJ,DC,DJ] [--modo nativo|escaneado|ambos] [--plan ciclico|limpio] [--few-shot].
 */
public final class SyntheticCli {

    static final String USO = "Uso: --salida DIR [--semilla N=42] [--cantidad N=5] [--desde N=1] "
        + "[--tipologias EC,EJ,DC,DJ] [--modo nativo|escaneado|ambos=ambos] [--plan ciclico|limpio=ciclico] "
        + "[--few-shot]";

    private SyntheticCli() {
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        int rc = ejecutar(args, System.out, System.err);
        if (rc != 0) {
            System.exit(rc);
        }
    }

    /** Ejecuta el CLI; devuelve 0 si salio bien, 2 por argumentos invalidos, 1 por error de E/S. */
    static int ejecutar(String[] args, PrintStream out, PrintStream err) {
        Opciones op;
        try {
            op = parsear(args);
        } catch (IllegalArgumentException e) {
            err.println("Argumentos invalidos: " + e.getMessage());
            err.println(USO);
            return 2;
        }
        try {
            List<String> ids = new BatchGenerator().generar(op);
            out.println("Generados " + ids.size() + " oficios en " + op.salida() + " (semilla " + op.semilla() + ")");
            return 0;
        } catch (IOException e) {
            err.println("Error de E/S: " + e.getMessage());
            return 1;
        }
    }

    static Opciones parsear(String[] args) {
        Path salida = null;
        long semilla = 42;
        int cantidad = 5;
        int desde = 1;
        List<Tipo> tipos = new ArrayList<>(List.of(Tipo.values()));
        Modo modo = Modo.AMBOS;
        Plan plan = Plan.CICLICO;
        boolean fewShot = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--few-shot")) {
                fewShot = true;
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("falta el valor de " + a);
            }
            String v = args[++i];
            switch (a) {
                case "--salida" -> salida = Path.of(v);
                case "--semilla" -> semilla = Long.parseLong(v);
                case "--cantidad" -> cantidad = Integer.parseInt(v);
                case "--desde" -> desde = Integer.parseInt(v);
                case "--tipologias" -> {
                    tipos = new ArrayList<>();
                    for (String t : v.split(",")) {
                        tipos.add(Tipo.parse(t));
                    }
                }
                case "--modo" -> modo = Modo.valueOf(v.toUpperCase(Locale.ROOT));
                case "--plan" -> plan = Plan.valueOf(v.toUpperCase(Locale.ROOT));
                default -> throw new IllegalArgumentException("opcion desconocida " + a);
            }
        }
        if (salida == null) {
            throw new IllegalArgumentException("--salida es obligatorio");
        }
        if (cantidad < 1 || desde < 1 || tipos.isEmpty()) {
            throw new IllegalArgumentException("cantidad y desde deben ser >= 1 y debe haber tipologias");
        }
        return new Opciones(salida, semilla, cantidad, desde, tipos, modo, plan, fewShot);
    }
}
