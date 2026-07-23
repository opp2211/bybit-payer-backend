package ru.maltsev.bybitpayerbackend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
                .andExpect(jsonPath("$.paths['/api/workspaces/{workspacePublicId}/withdrawals'].post").exists())
                .andExpect(jsonPath("$.paths['/api/workspaces/{workspacePublicId}/withdrawals/preview'].post").exists())
                .andExpect(jsonPath("$.paths['/api/workspaces/{workspacePublicId}/system/status'].get").exists())
                .andReturn();

        String generated = prettyPrint(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        Files.createDirectories(OPENAPI_OUTPUT.getParent());
        Files.writeString(OPENAPI_OUTPUT, generated, StandardCharsets.UTF_8);
    }

    private String prettyPrint(String json) throws Exception {
        JsonNode openApi = objectMapper.readTree(json);
        sortObjectAt(openApi, "components", "schemas", "CsrfToken", "properties");
        String prettyJson = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(openApi);
        return prettyJson.replace("\r\n", "\n").replace('\r', '\n') + "\n";
    }

    private void sortObjectAt(JsonNode root, String... path) {
        JsonNode current = root;
        for (String pathElement : path) {
            current = current.get(pathElement);
            if (current == null) {
                return;
            }
        }
        if (!(current instanceof ObjectNode objectNode)) {
            return;
        }

        var sortedProperties = objectNode.properties().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        objectNode.removeAll();
        sortedProperties.forEach(entry -> objectNode.set(entry.getKey(), entry.getValue()));
    }
}
