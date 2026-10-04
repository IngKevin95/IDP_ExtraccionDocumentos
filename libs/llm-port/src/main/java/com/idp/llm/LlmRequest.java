package com.idp.llm;

import com.idp.tenant.TenantId;
import org.springframework.core.io.Resource;
import java.time.Duration;
import java.util.List;

public record LlmRequest(
    TenantId tenantId,
    String prompt,
    List<Resource> images,
    Duration timeout
) {
    public LlmRequest {
        images = images != null ? List.copyOf(images) : List.of();
    }
    
    @Override
    public List<Resource> images() {
        return images != null ? List.copyOf(images) : List.of();
    }
}
