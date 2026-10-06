package com.idp.extraction.llm;

import com.idp.llm.LlmProvider;
import com.idp.llm.LlmRequest;
import com.idp.llm.LlmResponse;
import java.util.List;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeTypeUtils;

/**
 * Adaptador Spring AI detras del puerto {@link LlmProvider}. El {@link ChatModel} lo aporta el starter
 * seleccionado por configuracion (Vertex AI Gemini via {@code spring.ai.model.chat=google-genai} u
 * OpenAI-compatible via {@code spring.ai.model.chat=openai}). {@code modelOverride} permite apuntar el
 * proveedor secundario/cascada a otro modelo del mismo vendor. El timeout se aplica en el cliente HTTP
 * del starter, no por llamada.
 */
public final class SpringAiLlmProvider implements LlmProvider {

    private final ChatModel chatModel;
    private final String modelOverride;

    public SpringAiLlmProvider(ChatModel chatModel, String modelOverride) {
        this.chatModel = chatModel;
        this.modelOverride = modelOverride == null || modelOverride.isBlank() ? null : modelOverride;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        List<Media> media = request.images().stream()
            .map(SpringAiLlmProvider::toMedia).toList();
        UserMessage message = UserMessage.builder().text(request.prompt()).media(media).build();
        Prompt prompt = modelOverride == null
            ? new Prompt(message)
            : new Prompt(message, ChatOptions.builder().model(modelOverride).build());
        ChatResponse response = chatModel.call(prompt);
        var result = response == null ? null : response.getResult();
        if (result == null) {
            throw new IllegalStateException("Respuesta vacia del proveedor LLM");
        }
        var usage = response.getMetadata().getUsage();
        int in = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int out = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        String model = response.getMetadata().getModel();
        String finish = result.getMetadata() == null ? null : result.getMetadata().getFinishReason();
        return new LlmResponse(result.getOutput().getText(), model, finish, new LlmResponse.Usage(in, out));
    }

    private static Media toMedia(Resource image) {
        return new Media(MimeTypeUtils.IMAGE_PNG, image);
    }
}
