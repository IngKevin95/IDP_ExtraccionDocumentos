package com.idp.chat.service;

import com.idp.chat.config.ChatProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Huella SHA-256 de la configuracion efectiva que determina una respuesta (SEC-049, SEC-036): version del prompt,
 * modelos de chat y de embeddings, topK, umbrales de recuperacion, de cache y de cita minima, y limites de fragmento.
 * Se guarda por respuesta junto con el modelo y los tokens: permite reconstruir con que parametros se respondio.
 */
@Component
public final class ChatConfigFingerprint {

    private final String hash;

    public ChatConfigFingerprint(ChatProperties p) {
        String canonical = String.join(";",
                "prompt=" + PromptBuilder.PROMPT_VERSION,
                "llm=" + p.llm().model(),
                "fallback=" + p.llm().fallbackModel(),
                "embedding=" + p.llm().embeddingModel(),
                "dim=" + p.embeddingDimension(),
                "topK=" + p.topK(),
                "minSim=" + p.minSimilarity(),
                "cacheSim=" + p.cacheSimilarity(),
                "minQuote=" + GroundingVerifier.MIN_QUOTE_CHARS,
                "chunk=" + p.chunkMaxChars() + "/" + p.chunkOverlapChars());
        try {
            this.hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String hash() {
        return hash;
    }
}
