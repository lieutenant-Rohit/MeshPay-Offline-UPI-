package com.offline.payment.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
// FIX: was "tranaction" (missing 's') — corrected to "transactions"
@Table(name = "transactions",
        indexes = {@Index(name = "idx_packet_hash", columnList = "packetHash", unique = true)})
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String packetHash;

    @Column(nullable = false)
    private String senderVpa;

    @Column(nullable = false)
    private String receiverVpa;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String bridgeNodeId;

    @Column(nullable = false)
    private Instant signedAt;

    @Column(nullable = false)
    private Instant settleAt;

    @Column(nullable = false)
    private int hopCount;

    // FIX: removed "import org.hibernate.engine.spi.Status" which shadowed this enum.
    // The @Enumerated annotation now correctly maps to Transaction.Status below.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    public enum Status {
        SETTLED, REJECTED
    }

    public Transaction() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPacketHash() { return packetHash; }
    public void setPacketHash(String packetHash) { this.packetHash = packetHash; }
    public String getSenderVpa() { return senderVpa; }
    public void setSenderVpa(String senderVpa) { this.senderVpa = senderVpa; }
    public String getReceiverVpa() { return receiverVpa; }
    public void setReceiverVpa(String receiverVpa) { this.receiverVpa = receiverVpa; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Instant getSignedAt() { return signedAt; }
    public void setSignedAt(Instant signedAt) { this.signedAt = signedAt; }
    public Instant getSettleAt() { return settleAt; }
    public void setSettleAt(Instant settleAt) { this.settleAt = settleAt; }
    public int getHopCount() { return hopCount; }
    public void setHopCount(int hopCount) { this.hopCount = hopCount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getBridgeNodeId() { return bridgeNodeId; }
    public void setBridgeNodeId(String bridgeNodeId) { this.bridgeNodeId = bridgeNodeId; }
}