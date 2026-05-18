package com.offline.payment.security;

import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class PinVerificationService {

    //1-> Generate a random "Salt" for the user during setup
    public String generateRandomSalt(){
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }
    //2-> Combine the PIN and the Salt, then hash it
    public String hashPinWithSalt(String rawPin, String saltBase64)
    {
        try
        {
            //Ask the factory for SHA-256 Hashing machine
            MessageDigest digestMachine = MessageDigest.getInstance("SHA-256");

            //Combine the PIN and the salt into one string
            String combinedData = rawPin + saltBase64;

            //Throw it into the grinder and press start
            byte[] hashedBytes = digestMachine.digest(combinedData.getBytes());

            //Convert the raw bytes into a readable string to save in the DB
            return Base64.getEncoder().encodeToString(hashedBytes);
        }catch (NoSuchAlgorithmException e){
            throw new RuntimeException("Encryption algorithm not found", e);
        }
    }

    //3-> Called when the user clicks "Pay" offline
    public boolean verifyOfflinePin(String enteredPin, String storedSalt,String storedHash)
    {
        //Hashed what the user just typed using their saved salt
        String newlyCalculatedHash = hashPinWithSalt(enteredPin,storedSalt);

        //Check Validity
        return newlyCalculatedHash.equals(storedHash);
    }

}
