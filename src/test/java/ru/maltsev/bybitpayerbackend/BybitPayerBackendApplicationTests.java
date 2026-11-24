package ru.maltsev.bybitpayerbackend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitOrderBindingEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.TestBybitGatewayConfiguration;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.user.repository.UserRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

@SpringBootTest
@Import(TestBybitGatewayConfiguration.class)
class BybitPayerBackendApplicationTests {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BybitOrderBindingRepository bindingRepository;

    @Test
    void contextLoads() {
    }

    @Test
    @Transactional
    void claimsCompletionThanksOnlyOnce() {
        Instant now = Instant.parse("2026-11-24T10:00:00Z");
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setPublicId("AAA0123");
        workspace.setName("Order thanks");
        workspace.setOwner(userRepository.findByUsernameNormalized("operator").orElseThrow());
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        entityManager.persist(workspace);

        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setWorkspace(workspace);
        withdrawal.setAmountMinRub(BigDecimal.TEN);
        withdrawal.setAmountMaxRub(BigDecimal.TEN);
        withdrawal.setWithdrawalMethod(WithdrawalMethod.SBP);
        withdrawal.setPayerBankType(PayerBankType.TBANK_AUTO);
        withdrawal.setStatus(WithdrawalStatus.COMPLETED);
        withdrawal.setCreatedAt(now);
        entityManager.persist(withdrawal);

        BybitOrderBindingEntity binding = new BybitOrderBindingEntity();
        binding.setBybitOrderId("order-thanks");
        binding.setWorkspace(workspace);
        binding.setWithdrawalRequest(withdrawal);
        binding.setAmountRub(BigDecimal.TEN);
        binding.setCreatedAt(now);
        binding.markReleased();
        entityManager.persist(binding);
        entityManager.flush();
        entityManager.clear();

        assertThat(bindingRepository.findTop20ByStatusAndCompletionThanksRequestedTrueAndCompletionThanksProcessedFalseOrderById(
                OrderBindingStatus.RELEASED
        )).extracting(BybitOrderBindingEntity::getId).contains(binding.getId());
        assertThat(bindingRepository.claimCompletionThanks(binding.getId(), OrderBindingStatus.RELEASED)).isEqualTo(1);
        assertThat(bindingRepository.claimCompletionThanks(binding.getId(), OrderBindingStatus.RELEASED)).isZero();
        assertThat(bindingRepository.findTop20ByStatusAndCompletionThanksRequestedTrueAndCompletionThanksProcessedFalseOrderById(
                OrderBindingStatus.RELEASED
        )).extracting(BybitOrderBindingEntity::getId).doesNotContain(binding.getId());
    }
}
