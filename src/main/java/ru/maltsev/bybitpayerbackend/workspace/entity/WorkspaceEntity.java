package ru.maltsev.bybitpayerbackend.workspace.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "workspaces")
public class WorkspaceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 7)
    private String publicId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private UserEntity owner;

    @Column(name = "bybit_api_key_encrypted")
    private String bybitApiKeyEncrypted;

    @Column(name = "bybit_api_key_hash", length = 64)
    private String bybitApiKeyHash;

    @Column(name = "bybit_api_secret_encrypted")
    private String bybitApiSecretEncrypted;

    @Column(name = "bybit_p2p_ad_id", length = 128)
    private String bybitP2pAdId;

    @Column(name = "bybit_user_id", length = 128)
    private String bybitUserId;

    @Column(name = "bybit_account_id", length = 128)
    private String bybitAccountId;

    @Column(name = "bybit_nickname", length = 128)
    private String bybitNickname;

    @Column(name = "receipt_email")
    private String receiptEmail;

    @Column(name = "imap_host")
    private String imapHost;

    @Column(name = "imap_port")
    private Integer imapPort;

    @Column(name = "imap_username")
    private String imapUsername;

    @Column(name = "imap_password_encrypted")
    private String imapPasswordEncrypted;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
