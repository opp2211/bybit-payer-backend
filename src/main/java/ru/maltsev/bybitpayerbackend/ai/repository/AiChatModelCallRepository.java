package ru.maltsev.bybitpayerbackend.ai.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import ru.maltsev.bybitpayerbackend.ai.entity.AiChatModelCallEntity;

public interface AiChatModelCallRepository extends JpaRepository<AiChatModelCallEntity, Long> {
}
