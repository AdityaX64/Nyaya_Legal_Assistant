package com.nyaya.controller;

import com.nyaya.dto.ChatResponse;
import com.nyaya.dto.SourceReference;
import com.nyaya.service.RAGService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// WebMvc slice tests for ChatController endpoints
@WebMvcTest(ChatController.class)
public class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RAGService ragService;

    // Test health check endpoint status and chunk count
    @Test
    public void testHealth() throws Exception {
        when(ragService.isDatabaseAvailable()).thenReturn(true);
        when(ragService.count()).thenReturn(200);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.chunks").value(200));
    }

    // Test chat endpoint with question and citations
    @Test
    public void testChat() throws Exception {
        SourceReference ref = new SourceReference("BNS", "bns.pdf", "303", "Theft", 78, 78, "Whoever intends...");
        ChatResponse response = new ChatResponse("Under [BNS Section 303], theft is...", List.of(ref), 120L, List.of("303"), "default-session");

        when(ragService.askAIWithSources(any(), any(), any())).thenReturn(response);


        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"How does the BNS define theft?\",\"actFilter\":\"BNS\",\"conversationId\":\"session-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Under [BNS Section 303], theft is..."))
                .andExpect(jsonPath("$.sources[0].section").value("303"))
                .andExpect(jsonPath("$.conversationId").value("default-session"));
    }
}

