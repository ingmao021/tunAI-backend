package com.tunai.api.application.port.out;

/** Comprueba usuario y contraseña contra el almacén de credenciales (que solo guarda hashes). */
public interface CredentialVerifierPort {

    boolean verify(String username, String password);
}
