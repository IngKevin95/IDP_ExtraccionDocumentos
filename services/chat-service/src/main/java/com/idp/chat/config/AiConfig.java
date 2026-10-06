package com.idp.chat.config;

import com.idp.chat.infra.ResilientLlm;
import com.idp.chat.infra.SpringAiProviders;
import com.idp.llm.EmbeddingProvider;
import com.idp.llm.LlmProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Puertos de proveedor de IA (LlmProvider, EmbeddingProvider) sobre los modelos del starter Spring AI elegido. */
@Configuration
public class AiConfig {

    @Bean
    LlmProvider llmProvider(ObjectProvider<ChatModel> model) {
        return new SpringAiProviders.Llm(model);
    }

    /** LLM acotado: timeout real, bulkhead por tenant y failover al modelo secundario si esta configurado. */
    @Bean(destroyMethod = "close")
    ResilientLlm resilientLlm(LlmProvider primary, ObjectProvider<ChatModel> model, ChatProperties props) {
        String fallback = props.llm().fallbackModel();
        LlmProvider secondary = fallback.isBlank() ? null : new SpringAiProviders.Llm(model, fallback);
        return new ResilientLlm(primary, secondary, props.llm());
    }

    @Bean
    EmbeddingProvider embeddingProvider(ObjectProvider<EmbeddingModel> model, ChatProperties props) {
        return new SpringAiProviders.Embeddings(model, props.embeddingDimension());
    }

    @Bean
    TransactionTemplate chatTransactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
