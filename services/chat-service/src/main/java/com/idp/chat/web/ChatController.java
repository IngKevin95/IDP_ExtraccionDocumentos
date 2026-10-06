package com.idp.chat.web;

import com.idp.chat.service.ChatService;
import com.idp.chat.web.ChatDtos.ChatMessageResponse;
import com.idp.chat.web.ChatDtos.ChatSessionResponse;
import com.idp.chat.web.ChatDtos.CreateSessionRequest;
import com.idp.chat.web.ChatDtos.SendMessageRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API del chat (contracts/openapi/chat-service.yaml). Tenant y usuario salen siempre del JWT. */
@RestController
@RequestMapping("/v1/chat/sessions")
public class ChatController {

    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChatSessionResponse createSession(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody CreateSessionRequest request) {
        return ChatSessionResponse.of(chat.createSession(jwt, request.documentId()));
    }

    @PostMapping("/{sessionId}/messages")
    public ChatMessageResponse sendMessage(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable("sessionId") UUID sessionId,
                                           @Valid @RequestBody SendMessageRequest request) {
        return ChatMessageResponse.of(chat.sendMessage(jwt, sessionId, request.content()));
    }
}
