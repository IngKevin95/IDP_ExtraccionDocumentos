package com.idp.testsupport;

import com.idp.events.EventTopology;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ayuda de tests: entrega un evento al listener por el topico que la topologia asigna a su eventType (SEC-052). */
public final class Topics {

    private static final Pattern TYPE = Pattern.compile("\"eventType\"\s*:\s*\"([^\"]+)\"");

    private Topics() {
    }

    /** Topico del evento segun EventTopology; si el JSON es ilegible, un topico cualquiera del pipeline. */
    public static String of(String json) {
        Matcher m = TYPE.matcher(json == null ? "" : json);
        EventTopology topology = EventTopology.defaults();
        return m.find() && topology.knows(m.group(1)) ? topology.topicFor(m.group(1)) : "documentos.eventos";
    }

    public static void deliver(BiConsumer<String, String> listener, String json) {
        listener.accept(json, of(json));
    }
}
