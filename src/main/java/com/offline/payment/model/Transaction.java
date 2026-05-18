package com.offline.payment.model;

import jakarta.persistence.*;
import org.hibernate.engine.spi.Status;


import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "tranaction",
        // Created Unique Index on the packetHack column
        indexes = {@Index(name="idx_packet_hash",columnList="packetHash",unique=true)})

public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String packetHash; //SHA-256 hex of the encrypted packet

    @Column(nullable = false)
    private String senderVpa;

    @Column(nullable = false)
    private String receiverVpa;

    @Column(nullable = false,precision = 19,scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String bridgeNodeId; //Tells the bank which phone finally reached the internet

    @Column(nullable = false)
    private Instant signedAt; //Timestamp from user phone where they were offline

    @Column(nullable = false)
    private Instant settleAt;//Timestamp from the bank's server when the payment actually finished

    @Column(nullable = false)
    private int hopCount; // Tells hpw many phones the payment "jumped" through

    @Enumerated(EnumType.STRING)// Tells JPA to Save the Status as Text
    @Column(nullable = false)
    private Status status; //SETTLES -> Money Moves Successfully REJECTED -> The packet was valid, but Transaction Failed

    public enum Status{ SETTLED, REJECTED}

    public Transaction() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPacketHash() {
        return packetHash;
    }

    public void setPacketHash(String packetHash) {
        this.packetHash = packetHash;
    }

    public String getSenderVpa() {
        return senderVpa;
    }

    public void setSenderVpa(String senderVpa) {
        this.senderVpa = senderVpa;
    }

    public String getReceiverVpa() {
        return receiverVpa;
    }

    public void setReceiverVpa(String receiverVpa) {
        this.receiverVpa = receiverVpa;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(Instant signedAt) {
        this.signedAt = signedAt;
    }

    public Instant getSettleAt() {
        return settleAt;
    }

    public void setSettleAt(Instant settleAt) {
        this.settleAt = settleAt;
    }

    public int getHopCount() {
        return hopCount;
    }

    public void setHopCount(int hopCount) {
        this.hopCount = hopCount;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getBridgeNodeId() {
        return bridgeNodeId;
    }

    public void setBridgeNodeId(String bridgeNodeId) {
        this.bridgeNodeId = bridgeNodeId;
    }
}

/*
 * ========================================================================
 * THE "SECURITY DNA" OF THIS TRANSACTION
 * ========================================================================
 * * 1. THE PERMANENT INK (Audit Trail):
 * Think of the 'Account' table as a chalkboard that gets erased and
 * updated with new balances. This 'Transaction'
 * table is the permanent stone carving.
 * Even if a balance looks wrong, we can look here to see the exact
 * history of where every rupee went.
 *
 * 2. THE ONE-TIME TICKET (Idempotency Shield):
 * Because this is a Mesh Network, your payment is copied like a rumor
 * across many phones.
 * When those phones hit the internet, the bank might get 10 copies of
 * this same payment. The 'packetHash' is
 * the fingerprint that tells the bank: "I already handled this
 * specific person's request; ignore the other 9 copies".
 *
 * 3. THE DELIVERY MAP (Mesh Intelligence):
 * By recording 'hopCount' (how many phones touched it) and
 * 'bridgeNodeId' (who finally hit the internet), we can see exactly
 * how the payment traveled. It’s like
 * tracking a package to see which route the delivery driver took
 * through the city.
 *
 * 4. THE EXPIRATION DATE (Temporal Integrity):
 * We store 'signedAt' to know when the user originally hit "Pay"
 * while offline. If a hacker tries to
 * "replay" this same encrypted packet a week later, the bank will
 * see the old date and reject it because the "Freshness Window" is
 * closed.
 * ========================================================================
 */
