package ru.maltsev.bybitpayerbackend.receipt.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.SearchTerm;
import org.springframework.stereotype.Service;

import ru.maltsev.bybitpayerbackend.receipt.config.ReceiptMailProperties;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffMailReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptVerificationRequest;
import ru.maltsev.bybitpayerbackend.receipt.exception.ReceiptMailException;
import ru.maltsev.bybitpayerbackend.receipt.util.ReceiptText;

@Service
public class TinkoffReceiptMailService {

    private final ReceiptMailProperties properties;
    private final TinkoffReceiptValidator validator;

    public TinkoffReceiptMailService(ReceiptMailProperties properties, TinkoffReceiptValidator validator) {
        this.properties = properties;
        this.validator = validator;
    }

    public List<TinkoffMailReceiptValidationResult> findAndValidate(TinkoffReceiptVerificationRequest expected) {
        return findAndValidate(expected, Set.of(), false);
    }

    public List<TinkoffMailReceiptValidationResult> findForWithdrawal(
            TinkoffReceiptVerificationRequest expected,
            Set<String> ignoredReceiptKeys
    ) {
        return findAndValidate(expected, ignoredReceiptKeys, true);
    }

    private List<TinkoffMailReceiptValidationResult> findAndValidate(
            TinkoffReceiptVerificationRequest expected,
            Set<String> ignoredReceiptKeys,
            boolean claimMatchingReceipt
    ) {
        ensureEnabled();

        try {
            Session session = Session.getInstance(mailSessionProperties());
            try (Store store = session.getStore("imaps")) {
                store.connect(properties.getHost(), properties.getPort(), properties.getUsername(), properties.getPassword());
                Folder folder = store.getFolder(properties.getFolder());
                folder.open(Folder.READ_WRITE);
                try {
                    return validateFolderMessages(folder, expected, ignoredReceiptKeys, claimMatchingReceipt);
                } finally {
                    folder.close(false);
                }
            }
        } catch (MessagingException | IOException exception) {
            throw new ReceiptMailException("Не удалось проверить почту Gmail", exception);
        }
    }

    List<TinkoffMailReceiptValidationResult> validateFolderMessages(
            Folder folder,
            TinkoffReceiptVerificationRequest expected,
            Set<String> ignoredReceiptKeys,
            boolean claimMatchingReceipt
    ) throws MessagingException, IOException {
        List<Message> messages = Arrays.stream(folder.search(searchTerm()))
                .filter(this::isAllowedSender)
                .filter(this::hasAllowedSubject)
                .sorted(Comparator.comparing(this::receivedAtSafe).reversed())
                .toList();

        List<TinkoffMailReceiptValidationResult> results = new ArrayList<>();
        int inspectedMessages = 0;
        for (Message message : messages) {
            MailMessageMetadata metadata = metadata(message);
            List<MailAttachment> attachments = extractPdfAttachments(
                    message,
                    metadata,
                    "",
                    ignoredReceiptKeys
            );
            if (attachments.isEmpty()) {
                continue;
            }
            if (inspectedMessages >= Math.max(1, properties.getMaxMessages())) {
                break;
            }
            inspectedMessages++;
            for (MailAttachment attachment : attachments) {
                TinkoffReceiptValidationResult validationResult = validator.validatePdf(attachment.content(), expected);
                boolean recipientPhoneMatches = validator.matchesPhone(
                        validationResult.receipt().phone(),
                        expected.phone()
                );
                if (claimMatchingReceipt) {
                    message.setFlag(Flags.Flag.SEEN, recipientPhoneMatches);
                } else if (validationResult.valid() && properties.isMarkSeenOnValid()) {
                    message.setFlag(Flags.Flag.SEEN, true);
                }
                results.add(toMailResult(
                        metadata,
                        attachment,
                        recipientPhoneMatches,
                        validationResult
                ));
                if (claimMatchingReceipt && recipientPhoneMatches) {
                    return List.copyOf(results);
                }
            }
        }

        return List.copyOf(results);
    }

    private TinkoffMailReceiptValidationResult toMailResult(
            MailMessageMetadata metadata,
            MailAttachment attachment,
            boolean recipientPhoneMatches,
            TinkoffReceiptValidationResult validationResult
    ) {
        return new TinkoffMailReceiptValidationResult(
                validationResult.valid(),
                recipientPhoneMatches,
                attachment.receiptKey(),
                metadata.messageId(),
                metadata.subject(),
                metadata.from(),
                metadata.receivedAt(),
                attachment.fileName(),
                attachment.content(),
                validationResult.receipt(),
                validationResult.errors()
        );
    }

    private List<MailAttachment> extractPdfAttachments(
            Part part,
            MailMessageMetadata metadata,
            String partPath,
            Set<String> ignoredReceiptKeys
    ) throws MessagingException, IOException {
        List<MailAttachment> attachments = new ArrayList<>();
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int index = 0; index < multipart.getCount(); index++) {
                String childPath = partPath.isEmpty() ? String.valueOf(index) : partPath + "." + index;
                attachments.addAll(extractPdfAttachments(
                        multipart.getBodyPart(index),
                        metadata,
                        childPath,
                        ignoredReceiptKeys
                ));
            }
            return attachments;
        }

        String fileName = decode(part.getFileName());
        if (isPdfPart(part, fileName)) {
            String normalizedFileName = ReceiptText.hasText(fileName) ? fileName : "receipt.pdf";
            String receiptKey = receiptKey(metadata, partPath, normalizedFileName);
            if (ignoredReceiptKeys.contains(receiptKey)) {
                return attachments;
            }
            attachments.add(new MailAttachment(
                    receiptKey,
                    normalizedFileName,
                    part.getInputStream().readAllBytes()
            ));
        }
        return attachments;
    }

    private boolean isPdfPart(Part part, String fileName) throws MessagingException {
        return part.isMimeType("application/pdf")
                || (ReceiptText.hasText(fileName) && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf"));
    }

    private SearchTerm searchTerm() {
        SearchTerm from = new FromStringTerm(properties.getSender());
        if (!properties.isOnlyUnread()) {
            return from;
        }
        return new AndTerm(from, new FlagTerm(new Flags(Flags.Flag.SEEN), false));
    }

    boolean isAllowedSender(Message message) {
        try {
            String expectedSender = properties.getSender().toLowerCase(Locale.ROOT);
            Address[] addresses = message.getFrom();
            if (addresses == null) {
                return false;
            }
            return Arrays.stream(addresses)
                    .filter(InternetAddress.class::isInstance)
                    .map(InternetAddress.class::cast)
                    .map(InternetAddress::getAddress)
                    .filter(ReceiptText::hasText)
                    .map(address -> address.toLowerCase(Locale.ROOT))
                    .anyMatch(expectedSender::equals);
        } catch (MessagingException exception) {
            return false;
        }
    }

    boolean hasAllowedSubject(Message message) {
        try {
            String subjectPrefix = properties.getSubjectPrefix();
            if (!ReceiptText.hasText(subjectPrefix)) {
                return true;
            }
            String subject = decode(message.getSubject());
            return ReceiptText.hasText(subject) && subject.startsWith(subjectPrefix);
        } catch (MessagingException exception) {
            return false;
        }
    }

    private Instant receivedAtSafe(Message message) {
        try {
            Date date = message.getReceivedDate();
            if (date == null) {
                date = message.getSentDate();
            }
            return date == null ? Instant.EPOCH : date.toInstant();
        } catch (MessagingException exception) {
            return Instant.EPOCH;
        }
    }

    private String messageId(Message message) throws MessagingException {
        String[] messageIds = message.getHeader("Message-ID");
        if (messageIds == null || messageIds.length == 0) {
            return null;
        }
        return messageIds[0];
    }

    private String from(Message message) throws MessagingException {
        Address[] addresses = message.getFrom();
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        return decode(addresses[0].toString());
    }

    private MailMessageMetadata metadata(Message message) throws MessagingException {
        return new MailMessageMetadata(
                messageId(message),
                decode(message.getSubject()),
                from(message),
                receivedAtSafe(message)
        );
    }

    private String receiptKey(MailMessageMetadata metadata, String partPath, String fileName) {
        String source = ReceiptText.nullToEmpty(metadata.messageId())
                + "|" + ReceiptText.nullToEmpty(metadata.from())
                + "|" + ReceiptText.nullToEmpty(metadata.subject())
                + "|" + metadata.receivedAt()
                + "|" + partPath
                + "|" + fileName;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String decode(String value) {
        if (!ReceiptText.hasText(value)) {
            return value;
        }
        try {
            return MimeUtility.decodeText(value);
        } catch (Exception exception) {
            return value;
        }
    }

    Properties mailSessionProperties() {
        Properties sessionProperties = new Properties();
        sessionProperties.put("mail.store.protocol", "imaps");
        sessionProperties.put("mail.imaps.ssl.enable", "true");
        sessionProperties.put("mail.imaps.host", properties.getHost());
        sessionProperties.put("mail.imaps.port", String.valueOf(properties.getPort()));
        sessionProperties.put("mail.imaps.connectiontimeout", String.valueOf(properties.getConnectionTimeout().toMillis()));
        sessionProperties.put("mail.imaps.timeout", String.valueOf(properties.getReadTimeout().toMillis()));
        sessionProperties.put("mail.imaps.writetimeout", String.valueOf(properties.getReadTimeout().toMillis()));
        sessionProperties.put("mail.imaps.peek", "true");
        return sessionProperties;
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new ReceiptMailException("Проверка Gmail выключена");
        }
    }

    private record MailMessageMetadata(
            String messageId,
            String subject,
            String from,
            Instant receivedAt
    ) {
    }

    private record MailAttachment(String receiptKey, String fileName, byte[] content) {
    }
}
