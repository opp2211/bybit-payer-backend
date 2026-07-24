package ru.maltsev.bybitpayerbackend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import ru.maltsev.bybitpayerbackend.bybit.gateway.TestBybitGatewayConfiguration;

@SpringBootTest
@Import(TestBybitGatewayConfiguration.class)
class BybitPayerBackendApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void aiModelCallPayloadColumnsAcceptLargePrompts() {
        Long maxLength = jdbcTemplate.queryForObject("""
                select character_maximum_length
                from information_schema.columns
                where table_name = 'ai_chat_model_calls'
                  and column_name = 'prompt_json'
                """, Long.class);

        assertThat(maxLength).isGreaterThan(10_000L);
    }

    @Test
    void aiSessionMemoryColumnsAcceptLongText() {
        Long shortestTextColumn = jdbcTemplate.queryForObject("""
                select min(character_maximum_length)
                from information_schema.columns
                where table_name = 'ai_chat_sessions'
                  and column_name in (
                    'operator_handoff_reason',
                    'suggested_messages_json',
                    'suggested_reason',
                    'suggested_final_warning',
                    'last_decision_summary',
                    'conversation_summary'
                  )
                """, Long.class);

        assertThat(shortestTextColumn).isGreaterThan(10_000L);
    }

}
