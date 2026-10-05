package com.idp.extraction.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.extraction.core.ExtractionData;
import com.idp.extraction.store.PageContent;
import com.idp.extraction.typology.FieldDef;
import com.idp.extraction.typology.FieldType;
import com.idp.extraction.typology.TypologyDef;
import com.idp.llm.LlmResponse;
import com.idp.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmOrchestratorTest {

    @Test
    void testH8NonceSubstitutionAndMarkerEscaping() {
        ResilientLlmGateway gateway = mock(ResilientLlmGateway.class);
        ObjectMapper mapper = new ObjectMapper();
        LlmOrchestrator orchestrator = new LlmOrchestrator(gateway, mapper);

        ExtractionRun run = mock(ExtractionRun.class);
        when(run.tenant()).thenReturn(new TenantId("t1"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(gateway.generate(any(), promptCaptor.capture(), any(), any())).thenReturn(
                new LlmResponse("{\"fields\":{}}", "model", "stop", new LlmResponse.Usage(1, 1))
        );

        TypologyDef typology = new TypologyDef("T1", "Name", "Desc", 1, "FewShot", "{}", 
                List.of(new FieldDef("f1", FieldType.STRING, "desc", false, null, 0.0, 0.0)), List.of());
        PageContent page = new PageContent(1, new byte[0], "page text");

        String dangerousText = "Some {TEXT} and {NONCE} and <<< and >>> markers";

        orchestrator.extract(run, typology, List.of(page), dangerousText);

        String prompt = promptCaptor.getValue();
        
        assertThat(prompt).doesNotContain("<<< ");
        assertThat(prompt).contains("< < <");
        assertThat(prompt).contains("> > >");
        assertThat(prompt).contains("Some {TEXT} and {NONCE} and < < < and > > > markers");
    }
}
