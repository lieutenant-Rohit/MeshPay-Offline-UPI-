package com.offline.payment.security;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.*;
import java.util.Base64;

public class HybridCryptoService {
    //The Bank's Master Key Pair
    //Private Key never leaves the server!

    private final PrivateKey bankPrivateKey;
    private final PublicKey bankPublicKey;

    public HybridCryptoService()throws Exception
    {
        //Generates the Bank's RSA Kets when the server starts
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048); //2048-bit military grade security
        KeyPair pair = keyGen.generateKeyPair();
        this.bankPrivateKey = pair.getPrivate();
        this.bankPublicKey = pair.getPublic();
    }

    //Phone Downloads this key when it has internet
    public PublicKey getBankPublicKey()
    {
        return bankPublicKey;
    }

    /**
    *Called by the Phone(Offline)
    * This creates the "LOCKED SAFE"
    */

    public String encryptPayload(String plainTextJson)throws Exception{
        //1-> Phone creates a temporary,one-time-use AES key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey aesKey = keyGen.generateKey();

        //2-> Encrypt the large PaymentInstruction with AES
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12]; //GCM mode need a random Initialization Vector
        SecureRandom.getInstanceStrong().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);

        aesCipher.init(Cipher.ENCRYPT_MODE,aesKey,spec);
        byte[] encryptedData = aesCipher.doFinal(plainTextJson.getBytes());

        //3-> Encrpty the tiny AES using the Bank's RSA public key
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, bankPublicKey);
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

        //4-> Package it all together as a single String for MeshPacket
        //Format: IV : EncryptedAesKey : EncryptedData
        return Base64.getEncoder().encodeToString(iv) + ":" +
                Base64.getEncoder().encodeToString(encryptedAesKey) + ":" +
                Base64.getEncoder().encodeToString(encryptedData);
    }

}
