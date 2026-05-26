package com.offline.payment.security;

import jakarta.persistence.*;

@Entity
@Table(name = "server_keys")
public class ServerKeys {

    @Id
    @Column(name = "id")
    private String id = "default";

    @Column(name = "ed25519_private_encoded", nullable = false, length = 2048)
    private String ed25519PrivateEncoded;

    @Column(name = "ed25519_public_encoded", nullable = false, length = 2048)
    private String ed25519PublicEncoded;

    @Column(name = "x25519_private_encoded", nullable = false, length = 2048)
    private String x25519PrivateEncoded;

    @Column(name = "x25519_public_encoded", nullable = false, length = 2048)
    private String x25519PublicEncoded;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEd25519PrivateEncoded() { return ed25519PrivateEncoded; }
    public void setEd25519PrivateEncoded(String s) { this.ed25519PrivateEncoded = s; }
    public String getEd25519PublicEncoded() { return ed25519PublicEncoded; }
    public void setEd25519PublicEncoded(String s) { this.ed25519PublicEncoded = s; }
    public String getX25519PrivateEncoded() { return x25519PrivateEncoded; }
    public void setX25519PrivateEncoded(String s) { this.x25519PrivateEncoded = s; }
    public String getX25519PublicEncoded() { return x25519PublicEncoded; }
    public void setX25519PublicEncoded(String s) { this.x25519PublicEncoded = s; }
}
