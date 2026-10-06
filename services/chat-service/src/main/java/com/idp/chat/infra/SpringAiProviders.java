package com.idp.chat.infra;

import com.idp.chat.service.Exceptions.LlmUnavailableException;
import com.idp.llm.EmbeddingProvider;
import com.idp.llm.LlmProvider;
import com.idp.llm.LlmRequest;
import com.idp.llm.LlmResponse;
import com.idp.tenant.TenantId;
import java.util.List;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Adaptadores Spring AI detras de los puertos de libs/llm-port. El modelo lo aporta el starter elegido por
 * configuracion ({@code spring.ai.model.chat} y {@code spring.ai.model.embedding}); se resuelve en cada llamada, de modo
 * que sin proveedor configurado el chat falla cerrado con 503 en vez de impedir el arranque.
 */
public final class SpringAiProviders {

    private SpringAiProviders() {
    }

    public static final class Llm implements LlmProvider {
        private final ObjectProvider<ChatModel> model;

        public Llm(ObjectProvider<ChatModel> model) {
            this.model = model;
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            ChatModel chat = model.getIfAvailable();
            if (chat == null) {
                throw new LlmUnavailableException("Sin proveedor de chat configurado");
            }
            ChatResponse response = chat.call(new Prompt(UserMessage.builder().text(request.prompt()).build()));
            var result = response == null ? null : response.getResult();
            if (result == null || result.getOutput() == null) {
                return new LlmResponse(null, null, null, new LlmResponse.Usage(0, 0));
            }
            var usage = response.getMetadata().getUsage();
            int in = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            int out = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            String finish = result.getMetadata() == null ? null : result.getMetadata().getFinishReason();
            return new LlmResponse(result.getOutput().getText(), response.getMetadata().getModel(), finish,
                    new LlmResponse.Usage(in, out));
        }
    }

    public static final class Embeddings implements EmbeddingProvider {
        private final ObjectProvider<EmbeddingModel> model;
        private final int dimension;

        public Embeddings(ObjectProvider<EmbeddingModel> model, int dimension) {
            this.model = model;
            this.dimension = dimension;
        }

        @Override
        public int dimension() {
            return dimension;
        }

        @Override
        public List<float[]> embedBatch(TenantId tenantId, List<String> texts) {
            EmbeddingModel embedding = model.getIfAvailable();
            if (embedding == null) {
                throw new LlmUnavailableException("Sin proveedor de embeddings configurado");
            }
            return embedding.embed(texts);
        }
    }
}
