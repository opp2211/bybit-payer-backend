package ru.maltsev.bybitpayerbackend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import ru.maltsev.bybitpayerbackend.bybit.gateway.TestBybitGatewayConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestBybitGatewayConfiguration.class)
class OpenApiDocumentationTest {

    private static final Path OPENAPI_OUTPUT = Path.of("docs", "openapi.json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void generatesOpenApiDocumentation() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs").with(user("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/withdrawals'].post").exists())
                .andExpect(jsonPath("$.paths['/api/system/status'].get").exists())
                .andReturn();

        String generated = prettyPrint(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        Files.createDirectories(OPENAPI_OUTPUT.getParent());
        Files.writeString(OPENAPI_OUTPUT, generated, StandardCharsets.UTF_8);
    }

    private String prettyPrint(String json) throws Exception {
        String prettyJson = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(objectMapper.readTree(json));
        return prettyJson.replace("\r\n", "\n").replace('\r', '\n') + "\n";
    }
}
