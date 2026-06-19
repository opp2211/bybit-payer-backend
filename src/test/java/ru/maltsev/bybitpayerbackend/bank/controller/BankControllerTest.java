package ru.maltsev.bybitpayerbackend.bank.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ru.maltsev.bybitpayerbackend.bank.dto.BankResponse;
import ru.maltsev.bybitpayerbackend.bank.service.BankService;

@WebMvcTest(BankController.class)
class BankControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BankService bankService;

    @Test
    @WithMockUser
    void returnsEnabledBanksInConfiguredOrder() throws Exception {
        when(bankService.getEnabledBanks()).thenReturn(List.of(
                new BankResponse("SBERBANK", "Сбербанк"),
                new BankResponse("VTB", "ВТБ")
        ));

        mockMvc.perform(get("/api/banks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("SBERBANK"))
                .andExpect(jsonPath("$[0].title").value("Сбербанк"))
                .andExpect(jsonPath("$[1].code").value("VTB"))
                .andExpect(jsonPath("$[1].title").value("ВТБ"));
    }
}
