package com.offline.payment.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The ultimate envelope that travels over Bluetooth.
 * Contains Routing Data for the crowd, and Security Data for the Bank.
 */
public class MeshPacket {

    // --- ROUTING DATA (For the offline phones) ---
    @NotBlank
    private String packetId; // Prevents infinite loops in the crowd

    @Min(0)
    private int ttl; // Time To Live: How many more hops this packet can make

    @NotNull
    private Long createdAt; // Timestamp of when it was created

    // --- SECURITY DATA (For the Bank Server) ---
    @NotBlank
    private String senderVpa; // Written on the outside so the Bank can fetch the Public Key

    @NotBlank
    private String signature; // The Digital Wax Seal (Proves identity)

    @NotBlank
    private String ciphertext; // The locked safe containing the PaymentInstruction


    // --- Constructors ---
    public MeshPacket() {
    }

    // --- Getters and Setters ---
    public String getPacketId() { return packetId; }
    public void setPacketId(String packetId) { this.packetId = packetId; }

    public int getTtl() { return ttl; }
    public void setTtl(int ttl) { this.ttl = ttl; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public String getSenderVpa() { return senderVpa; }
    public void setSenderVpa(String senderVpa) { this.senderVpa = senderVpa; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getCiphertext() { return ciphertext; }
    public void setCiphertext(String ciphertext) { this.ciphertext = ciphertext; }
}