package com.tunai.api.application.port.out;

import java.util.Optional;

/** Comprueba la firma y la vigencia de un token emitido por {@link SessionIssuerPort}. */
public interface SessionVerifierPort {

    /** El usuario del token, o vacío si la firma no es válida o el token caducó. */
    Optional<String> verify(String token);
}
